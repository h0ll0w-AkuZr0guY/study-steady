#!/usr/bin/env python3
"""Explicit, non-destructive ADB deployment for the Study Steady debug Android app."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tarfile
import time
import uuid
import zipfile

from game_archive import (ArchiveError, CHUNK, MANIFEST, inspect_source, manifest_bytes,
                          parse_manifest, read_archive_manifest, _regular_file)

PACKAGE = "com.winlator"
ACTIVITY = "com.winlator.StudySteadyActivity"
FINISH_ACTION = "com.avgamer.study.FINISH_IMPORT"
REMOTE_ZIP = "/sdcard/Download/StudySteady.zip"
HEADROOM = 1024**3
READY = b"STUDY_IMPORT_READY\n"


def raw_shell_argv(adb: str, serial: str, command: str, package=PACKAGE) -> list[str]:
    """adb shell joins arguments verbatim; quote the sh -c program exactly once.

    This is for TEXT commands only. On Windows adb shell reads stdin in text
    mode (Ctrl-Z/LF corruption), so binary input uses exec-in direct arguments.
    """
    if package != PACKAGE:
        raise ArchiveError("The native runtime requires package com.winlator")
    return [adb, "-s", serial, "shell", "-T", f"run-as {package} sh -c {shlex.quote(command)}"]


def probe_stream(adb: str, serial: str) -> dict:
    """Verify binary input via a unique 4096-byte cache file, then remove it."""
    payload = bytes(range(256)) * 16
    expected = hashlib.sha256(payload).hexdigest()
    remote = "cache/study-stream-probe-" + uuid.uuid4().hex + ".bin"
    try:
        # exec-in escapes each argument itself: NEVER manually quote its args.
        # Fixed-count dd stops before EOF; exec-in cannot half-close and wait.
        subprocess.run([adb, "-s", serial, "exec-in", "run-as", PACKAGE, "dd",
                        "of=" + remote, "bs=4096", "count=1"], input=payload,
                       capture_output=True, timeout=15, check=True)
        result = subprocess.run(raw_shell_argv(adb, serial, "sha256sum " + remote),
                                stdin=subprocess.DEVNULL, capture_output=True, timeout=15, check=True)
        if result.stdout.split()[:1] != [expected.encode("ascii")]:
            raise ArchiveError("Binary ADB pipe probe failed: device SHA-256 differs")
    finally:
        # Generated UUID path is fixed for this probe; no original/user file is removed.
        subprocess.run(raw_shell_argv(adb, serial, "rm -f " + remote), stdin=subprocess.DEVNULL,
                       capture_output=True, timeout=15, check=True)
    return {"serial": serial, "binaryPipeVerified": True, "probeBytes": len(payload),
            "sha256": expected, "temporaryProbeRemoved": True}


def prepare_staging(adb, serial, staging, target, container_id, package=PACKAGE):
    """Refuse to read/send game bytes until the device confirms staging exists."""
    command = (f"test ! -e {staging} && test ! -e {target} && "
               f"test -d files/rootfs/home/xuser-{container_id}/.wine/drive_c && "
               "command -v tar >/dev/null && "
               f"mkdir {staging} && printf 'STUDY_IMPORT_READY\\n'")
    result = subprocess.run(raw_shell_argv(adb, serial, command, package),
                            stdin=subprocess.DEVNULL, capture_output=True, timeout=15)
    if result.returncode or result.stdout.strip() != READY.strip():
        detail = result.stderr.decode("utf-8", errors="replace")
        raise ArchiveError(f"Device import staging failed before transfer: {detail}")


def check_final_manifest(adb, serial, staging, manifest, package=PACKAGE):
    """The final TAR member proves the extractor consumed all preceding data."""
    expected = hashlib.sha256(manifest_bytes(manifest)).hexdigest().encode("ascii")
    result = subprocess.run(raw_shell_argv(adb, serial, "sha256sum " + staging + "/" + MANIFEST, package),
                            stdin=subprocess.DEVNULL, capture_output=True, timeout=15)
    if result.returncode or result.stdout.split()[:1] != [expected]:
        detail = result.stderr.decode("utf-8", errors="replace")
        raise ArchiveError("Device did not extract the complete final manifest; import was not launched. " + detail)


def adb_binary(value=None) -> str:
    value = value or shutil.which("adb")
    if not value:
        raise ArchiveError("adb not found; supply --adb with the platform-tools adb executable")
    return str(value)


def select_serial(adb: str, explicit: str | None) -> str:
    result = subprocess.run([adb, "devices"], check=True, capture_output=True, text=True)
    devices = []
    states = {}
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            states[parts[0]] = parts[1]
            if parts[1] == "device":
                devices.append(parts[0])
    if explicit:
        if states.get(explicit) != "device":
            raise ArchiveError(f"Device {explicit!r} is not authorized and online (state: {states.get(explicit, 'missing')})")
        return explicit
    if len(devices) != 1:
        raise ArchiveError("Exactly one authorized ADB device is required, or choose --serial explicitly")
    return devices[0]


def free_bytes(adb: str, serial: str) -> int:
    result = subprocess.run([adb, "-s", serial, "shell", "df", "-k", "/data"],
                            check=True, capture_output=True, text=True)
    for line in reversed(result.stdout.splitlines()):
        parts = line.split()
        if len(parts) >= 6 and parts[1].isdigit() and parts[3].isdigit():
            return int(parts[3]) * 1024
    raise ArchiveError("Could not read /data free space from adb df")


def container_paths(container_id: int) -> tuple[str, str]:
    if isinstance(container_id, bool) or not isinstance(container_id, int) or not 1 <= container_id <= 9999:
        raise ArchiveError("Container id must be an integer from 1 to 9999")
    parent = f"files/rootfs/home/xuser-{container_id}/.wine/drive_c"
    return parent + "/.StudySteady-import", parent + "/StudySteady"


def direct_manifest(source: Path, archive: Path | None = None, manifest_path: Path | None = None) -> dict:
    if archive and manifest_path:
        raise ArchiveError("Choose one manifest source: --archive or --manifest")
    if archive:
        with zipfile.ZipFile(archive) as reader:
            manifest = read_archive_manifest(reader)
    elif manifest_path:
        manifest = parse_manifest(manifest_path.read_bytes())
    else:
        plan = inspect_source(source)
        files = []
        for item in plan["files"]:
            digest = hashlib.sha256()
            with source.joinpath(*item["path"].split("/")).open("rb") as reader:
                while chunk := reader.read(CHUNK):
                    digest.update(chunk)
            files.append({**item, "sha256": digest.hexdigest()})
        manifest = {"schema": 1, "gameId": plan["gameId"], "launcher": plan["launcher"],
                    "files": files, "totalBytes": plan["totalBytes"]}
        parse_manifest(manifest_bytes(manifest))
    source_plan = inspect_source(source, launcher=manifest["launcher"])
    source_files = {item["path"]: item["size"] for item in source_plan["files"]}
    if any(source_files.get(item["path"]) != item["size"] for item in manifest["files"]):
        raise ArchiveError("Source files or sizes do not match the selected manifest")
    return manifest


class HashingReader:
    def __init__(self, raw, progress):
        self.raw = raw
        self.digest = hashlib.sha256()
        self.progress = progress
        self.count = 0

    def read(self, size=-1):
        data = self.raw.read(size)
        self.digest.update(data)
        self.count += len(data)
        self.progress(len(data))
        return data


def stream_direct(adb: str, serial: str, source: Path, manifest: dict, container_id: int,
                  *, finish_import=False, package=PACKAGE) -> dict:
    available = free_bytes(adb, serial)
    required = manifest["totalBytes"] + HEADROOM
    if available < required:
        raise ArchiveError(f"Device needs {required:,} bytes free (game + 1 GiB); only {available:,} available")
    staging, target = container_paths(container_id)
    if package != PACKAGE:
        raise ArchiveError("The native runtime requires package com.winlator")
    probe_stream(adb, serial)
    # Text-only shell preflight; game binary data bypasses shell's Windows stdin mode.
    prepare_staging(adb, serial, staging, target, container_id, package)
    argv = [adb, "-s", serial, "exec-in", "run-as", package, "tar", "-xf", "-", "-C", staging]
    completed = 0
    last = time.monotonic()
    def progress(size):
        nonlocal completed, last
        if process.poll() is not None:
            errors.flush()
            errors.seek(0)
            raise ArchiveError("Device extractor exited during transfer: " + errors.read(8192).decode("utf-8", errors="replace"))
        completed += size
        now = time.monotonic()
        if now - last >= 5:
            last = now
            print(f"Transfer {completed * 100 / max(1, manifest['totalBytes']):.1f}% "
                  f"({completed:,}/{manifest['totalBytes']:,} bytes)", file=sys.stderr, flush=True)
    # Capture stderr in a temporary file so a verbose device cannot deadlock a pipe.
    import tempfile
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=errors)
        try:
            with tarfile.open(fileobj=process.stdin, mode="w|", format=tarfile.PAX_FORMAT, bufsize=CHUNK) as tar:
                for item in manifest["files"]:
                    path = source.joinpath(*item["path"].split("/"))
                    before = _regular_file(path)
                    info = tarfile.TarInfo(item["path"])
                    info.size = item["size"]
                    info.mode = 0o644
                    with path.open("rb") as raw:
                        reader = HashingReader(raw, progress)
                        tar.addfile(info, reader)
                    after = path.stat()
                    if reader.count != item["size"] or reader.digest.hexdigest() != item["sha256"]:
                        raise ArchiveError(f"Source changed or differs from archive SHA-256: {item['path']!r}")
                    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
                        raise ArchiveError(f"Source changed while transferring: {item['path']!r}")
                data = manifest_bytes(manifest)
                info = tarfile.TarInfo(MANIFEST)
                info.size = len(data)
                info.mode = 0o644
                tar.addfile(info, io.BytesIO(data))
            process.stdin.close()
            returncode = process.wait(timeout=30)
            if returncode:
                errors.seek(0)
                raise ArchiveError("ADB tar extraction failed: " + errors.read(8192).decode("utf-8", errors="replace"))
        except BaseException:
            if process.poll() is None:
                process.terminate()
            process.wait()
            raise
    check_final_manifest(adb, serial, staging, manifest, package)
    if finish_import:
        subprocess.run([adb, "-s", serial, "shell", "am", "start", "-n", f"{package}/{ACTIVITY}",
                        "-a", FINISH_ACTION], check=True)
    return {"serial": serial, "staging": staging, "fileCount": len(manifest["files"]),
            "totalBytes": completed, "deviceManifestVerified": True, "finishImportRequested": finish_import}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", help="Path to adb executable")
    parser.add_argument("--serial", help="Explicit device serial; mandatory when multiple devices are connected")
    parser.add_argument("--package", default=PACKAGE, choices=[PACKAGE], help="Android package; native runtime requires com.winlator")
    commands = parser.add_subparsers(dest="command", required=True)
    deploy = commands.add_parser("deploy", help="Plan ZIP/APK deployment; mutations require explicit flags")
    deploy.add_argument("--apk", type=Path)
    deploy.add_argument("--archive", type=Path)
    deploy.add_argument("--install", action="store_true", help="Execute adb install -r")
    deploy.add_argument("--push", action="store_true", help="Execute adb push to the device Download directory")
    deploy.add_argument("--launch", action="store_true", help="Start the Study Steady activity")
    direct = commands.add_parser("deploy-direct", help="Stream directly to private app storage, avoiding a second device copy")
    direct.add_argument("source", type=Path)
    direct.add_argument("--archive", type=Path, help="Read the manifest from the already packaged ZIP")
    direct.add_argument("--manifest", type=Path, help="Read an exported study-manifest.json")
    direct.add_argument("--container-id", type=int, default=1)
    direct.add_argument("--execute", action="store_true", help="Execute the transfer; otherwise print a plan")
    direct.add_argument("--finish-import", action="store_true", help="Ask the app to validate and finish the imported game")
    commands.add_parser("probe-stream", help="Check binary stdin via a temporary 4096-byte cache file; removes that exact file")
    args = parser.parse_args(argv)
    try:
        adb = adb_binary(args.adb)
        if args.command == "probe-stream":
            result = probe_stream(adb, select_serial(adb, args.serial))
        elif args.command == "deploy":
            if args.install and not args.apk or args.push and not args.archive:
                raise ArchiveError("--install needs --apk; --push needs --archive")
            actions = []
            if args.apk:
                if not args.apk.is_file():
                    raise ArchiveError("APK does not exist")
                actions.append({"action": "install", "execute": args.install, "path": str(args.apk.resolve())})
            if args.archive:
                with zipfile.ZipFile(args.archive) as archive:
                    manifest = read_archive_manifest(archive)
                actions.append({"action": "push", "execute": args.push, "path": str(args.archive.resolve()), "remote": REMOTE_ZIP})
            if args.launch:
                actions.append({"action": "launch", "execute": True})
            serial = select_serial(adb, args.serial) if args.install or args.push or args.launch else args.serial
            if args.install:
                subprocess.run([adb, "-s", serial, "install", "-r", str(args.apk.resolve())], check=True)
            if args.push:
                available = free_bytes(adb, serial)
                needed = args.archive.stat().st_size + manifest["totalBytes"] + HEADROOM
                if available < needed:
                    raise ArchiveError(f"ZIP plus import need {needed:,} free bytes; only {available:,} available. Use deploy-direct.")
                subprocess.run([adb, "-s", serial, "push", str(args.archive.resolve()), REMOTE_ZIP], check=True)
            if args.launch:
                subprocess.run([adb, "-s", serial, "shell", "am", "start", "-n", f"{args.package}/{ACTIVITY}"], check=True)
            result = {"serial": serial, "actions": actions}
        else:
            source = args.source.resolve(strict=True)
            manifest = direct_manifest(source, args.archive, args.manifest)
            staging, target = container_paths(args.container_id)
            if args.finish_import and not args.execute:
                raise ArchiveError("--finish-import requires --execute")
            if args.execute:
                serial = select_serial(adb, args.serial)
                result = stream_direct(adb, serial, source, manifest, args.container_id, finish_import=args.finish_import, package=args.package)
            else:
                result = {"dryRun": True, "source": str(source), "serial": args.serial,
                          "staging": staging, "target": target, "totalBytes": manifest["totalBytes"],
                          "requiredFreeBytes": manifest["totalBytes"] + HEADROOM,
                          "fileCount": len(manifest["files"]), "debugApkRequired": True}
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except (ArchiveError, OSError, zipfile.BadZipFile, subprocess.SubprocessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
