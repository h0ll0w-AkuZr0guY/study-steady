#!/usr/bin/env python3
"""Run StudyArchive JVM integration tests with synthetic assets only.

Needs a JDK (JAVA_HOME or PATH). On first run downloads a pinned org.json JAR
from Maven Central into the user's cache; --json-jar supports offline runs.
Compilation and all fixture mutations occur inside a fresh temporary directory.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

JSON_VERSION = "20250517"
JSON_SHA256 = "3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796"
JSON_URL = f"https://repo.maven.apache.org/maven2/org/json/json/{JSON_VERSION}/json-{JSON_VERSION}.jar"
PROJECT = Path(__file__).resolve().parents[1]


def jdk_tool(name: str) -> str:
    executable = name + (".exe" if os.name == "nt" else "")
    if os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / executable
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if found:
        return found
    raise RuntimeError(f"Cannot find {name}. Install a JDK and set JAVA_HOME or PATH.")


def json_dependency(explicit: Path | None) -> Path:
    if explicit:
        jar = explicit.resolve()
    else:
        cache_base = Path(os.environ.get("LOCALAPPDATA") or os.environ.get("XDG_CACHE_HOME")
                          or str(Path.home() / ".cache"))
        jar = cache_base / "StudySteadyMobile" / "test-dependencies" / f"json-{JSON_VERSION}.jar"
        if not jar.is_file():
            jar.parent.mkdir(parents=True, exist_ok=True)
            print(f"Downloading org.json {JSON_VERSION} from Maven Central ...", flush=True)
            temporary = jar.with_suffix(".jar.download")
            try:
                with urllib.request.urlopen(JSON_URL, timeout=45) as response:
                    with temporary.open("wb") as output:
                        shutil.copyfileobj(response, output)
                if hashlib.sha256(temporary.read_bytes()).hexdigest() != JSON_SHA256:
                    raise RuntimeError("Downloaded org.json JAR failed SHA-256 verification.")
                temporary.replace(jar)
            finally:
                if temporary.is_file():
                    temporary.unlink()
    if not jar.is_file():
        raise RuntimeError(f"org.json JAR does not exist: {jar}")
    if hashlib.sha256(jar.read_bytes()).hexdigest() != JSON_SHA256:
        raise RuntimeError(f"org.json JAR differs from pinned version {JSON_VERSION}: {jar}")
    return jar


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json-jar", type=Path,
                        help=f"Existing unmodified org.json {JSON_VERSION} JAR for offline testing")
    args = parser.parse_args()
    javac, java = jdk_tool("javac"), jdk_tool("java")
    jar = json_dependency(args.json_jar)
    helper = (PROJECT / "upstream" / "winlator-app-main" / "app" / "src" / "main"
              / "java" / "com" / "winlator" / "study" / "StudyArchive.java")
    harness = PROJECT / "tests" / "java" / "StudyArchiveQa.java"
    if not helper.is_file() or not harness.is_file():
        raise RuntimeError("StudyArchive source or JVM test harness is missing.")
    with tempfile.TemporaryDirectory(prefix="study-archive-tests-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([javac, "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-Xlint:-options", "-cp", str(jar), "-d", str(classes),
                        str(helper), str(harness)], check=True)
        subprocess.run([java, "-cp", str(classes) + os.pathsep + str(jar),
                        "StudyArchiveQa", str(work / "fixtures")], check=True)
        library = helper.with_name("GameLibrary.java")
        library_qa = PROJECT / "tests" / "java" / "GameLibraryQa.java"
        subprocess.run([javac, "-encoding", "UTF-8", "-cp", str(jar), "-d", str(classes),
                        str(helper), str(library), str(library_qa)], check=True)
        subprocess.run([java, "-cp", str(classes) + os.pathsep + str(jar), "GameLibraryQa"], check=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Archive test runner failed: {error}", file=sys.stderr)
        sys.exit(1)
