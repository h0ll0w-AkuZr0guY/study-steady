#!/usr/bin/env python3
"""Create and verify a local, offline Study Steady archive (Python stdlib only)."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
import unicodedata
import zipfile

MANIFEST = "study-manifest.json"
GAME_ID = "study-steady"
LAUNCHER = "ststeady_patch.exe"
ROOT_FILES = frozenset(("ststeady.exe", LAUNCHER, "emotedriver.dll", "hook.dll", "AymKze.txt"))
REQUIRED_FILES = frozenset(("script.noa", "psb.noa"))
TRANSLATION_DIRECTORY = "补丁/汉化补丁/"
TRANSLATION_FILES = frozenset(TRANSLATION_DIRECTORY + name for name in (LAUNCHER, "hook.dll", "patchcn.noa"))
UNSAFE_SAVE_SUFFIXES = frozenset((".exe", ".dll", ".bat", ".cmd", ".ps1", ".sh", ".apk", ".so", ".com", ".vbs", ".js", ".jar", ".msi", ".scr"))
MAX_BYTES = 64 * 1024**3
MAX_MANIFEST = 1024**2
CHUNK = 4 * 1024**2
WINDOWS_RESERVED = re.compile(r"^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)", re.I)


class ArchiveError(ValueError):
    pass


def collision_key(path: str) -> str:
    return unicodedata.normalize("NFC", path).casefold()


def validate_relative_path(path: str) -> str:
    """Validate portable relative filenames, independent of game/save allowlists."""
    if not isinstance(path, str) or not path or len(path) > 1024:
        raise ArchiveError("File path must be a nonempty string, at most 1024 characters")
    if "\\" in path or ":" in path or path.startswith("/"):
        raise ArchiveError(f"Absolute or Windows-style archive path is forbidden: {path!r}")
    parts = path.split("/")
    if len(parts) > 64:
        raise ArchiveError("Archive path exceeds 64 components")
    for component in parts:
        if len(component) > 255 or component in ("", ".", "..") or component[-1:] in (" ", "."):
            raise ArchiveError(f"Unsafe archive path component: {path!r}")
        if any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in component):
            raise ArchiveError(f"Control character in archive path: {path!r}")
        if any(char in '<>"|?*' for char in component) or WINDOWS_RESERVED.match(component):
            raise ArchiveError(f"Unsafe Windows filename: {path!r}")
    return path


def validate_path(path: str, *, allow_manifest: bool = False) -> str:
    """Validate relative archive names before reading or writing any game data."""
    validate_relative_path(path)
    parts = path.split("/")
    if len(parts) == 1:
        if allow_manifest and path == MANIFEST:
            return path
        if path in ROOT_FILES or PurePosixPath(path).suffix.lower() == ".noa":
            return path
        raise ArchiveError(f"Root game file is outside the allowlist: {path!r}")
    if path in TRANSLATION_FILES:
        return path
    if parts[0] == "savedata" and PurePosixPath(path).suffix.lower() not in UNSAFE_SAVE_SUFFIXES:
        return path
    raise ArchiveError(f"Only approved game/translation files and safe savedata files are allowed: {path!r}")


def validate_launcher(launcher: str) -> str:
    validate_path(launcher)
    if launcher not in ("ststeady.exe", LAUNCHER, TRANSLATION_DIRECTORY + LAUNCHER):
        raise ArchiveError("Launcher must be an allowed game executable")
    return launcher


def _integer(value, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= MAX_BYTES:
        raise ArchiveError(f"Invalid {field}; expected a nonnegative integer <= 64 GiB")
    return value


def parse_manifest(raw: bytes) -> dict:
    if len(raw) > MAX_MANIFEST:
        raise ArchiveError("Manifest exceeds 1 MiB")
    try:
        # Reject duplicate object keys instead of silently keeping the last one.
        def pairs(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise ArchiveError(f"Duplicate manifest key: {key!r}")
                result[key] = value
            return result
        manifest = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ArchiveError(f"Invalid UTF-8 JSON manifest: {exc}") from exc
    if not isinstance(manifest, dict):
        raise ArchiveError("Manifest must be a JSON object")
    if type(manifest.get("schema")) is not int or manifest.get("schema") != 1:
        raise ArchiveError("Unsupported manifest schema")
    if manifest.get("gameId") != GAME_ID:
        raise ArchiveError("Manifest gameId is unsupported")
    launcher = validate_launcher(manifest.get("launcher"))
    files = manifest.get("files")
    if not isinstance(files, list) or not 1 <= len(files) <= 4096:
        raise ArchiveError("Manifest must contain between 1 and 4096 files")
    names = set()
    total = 0
    for item in files:
        if not isinstance(item, dict):
            raise ArchiveError("Each manifest file must be an object")
        path = validate_path(item.get("path"))
        key = collision_key(path)
        if key in names:
            raise ArchiveError(f"Duplicate or case-colliding manifest file: {path!r}")
        names.add(key)
        total += _integer(item.get("size"), f"size of {path!r}")
        digest = item.get("sha256")
        if not isinstance(digest, str) or not re.fullmatch("[0-9a-f]{64}", digest):
            raise ArchiveError(f"Invalid SHA-256 for {path!r}")
    if not (REQUIRED_FILES | {launcher}).issubset({item["path"] for item in files}):
        raise ArchiveError("Selected launcher, script.noa, or psb.noa is missing")
    if total > MAX_BYTES or _integer(manifest.get("totalBytes"), "totalBytes") != total:
        raise ArchiveError("Manifest totalBytes does not match its files")
    return manifest


def manifest_bytes(manifest: dict) -> bytes:
    return (json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def _regular_file(path: Path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or path.is_symlink():
        raise ArchiveError(f"Game files must be regular files, without symlinks: {path}")
    # A Windows junction/reparse point must not redirect to other directories.
    if getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0):
        raise ArchiveError(f"Reparse points are forbidden: {path}")
    return info


def inspect_source(source: str | Path, *, include_saves: bool = True, launcher: str = LAUNCHER) -> dict:
    validate_launcher(launcher)
    source = Path(source).resolve(strict=True)
    if not source.is_dir():
        raise ArchiveError("Game source must be a directory")
    files = []
    excluded = []
    for path in sorted(source.iterdir(), key=lambda item: item.name.casefold()):
        relative = path.name
        if path.is_file() and (relative in ROOT_FILES or path.suffix.lower() == ".noa"):
            validate_path(relative)
            info = _regular_file(path)
            files.append({"path": relative, "size": info.st_size})
        elif path.name == "savedata" and include_saves:
            if path.is_symlink() or getattr(path.lstat(), "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0):
                raise ArchiveError("savedata must not be a symlink or reparse point")
            if not path.is_dir():
                raise ArchiveError("savedata must be a directory")
            for current, dirs, names in os.walk(path, followlinks=False):
                dirs.sort()
                for directory in dirs:
                    candidate = Path(current) / directory
                    info = candidate.lstat()
                    if candidate.is_symlink() or getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0):
                        raise ArchiveError(f"Save directory is a symlink/reparse point: {candidate}")
                for filename in sorted(names):
                    candidate = Path(current) / filename
                    relative = candidate.relative_to(source).as_posix()
                    try:
                        validate_path(relative)
                    except ArchiveError:
                        excluded.append(relative)
                        continue
                    info = _regular_file(candidate)
                    files.append({"path": relative, "size": info.st_size})
        else:
            excluded.append(relative + ("/" if path.is_dir() else ""))
    # Preserve only the three specifically authorized translation files. Other
    # patch folders, installers and optical-drive utilities stay excluded.
    for relative in sorted(TRANSLATION_FILES):
        candidate = source.joinpath(*relative.split("/"))
        if candidate.exists():
            for parent in (candidate.parent, candidate.parent.parent):
                info = parent.lstat()
                if parent.is_symlink() or getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0):
                    raise ArchiveError(f"Translation directory is a symlink/reparse point: {parent}")
            info = _regular_file(candidate)
            files.append({"path": relative, "size": info.st_size})
    files.sort(key=lambda item: item["path"])
    names = [collision_key(item["path"]) for item in files]
    if len(set(names)) != len(names):
        raise ArchiveError("Source contains case-colliding or Unicode-equivalent filenames")
    if not (REQUIRED_FILES | {launcher}).issubset({item["path"] for item in files}):
        raise ArchiveError("Selected launcher, script.noa, or psb.noa is missing")
    total = sum(item["size"] for item in files)
    if total > MAX_BYTES or len(files) > 4096:
        raise ArchiveError("Game exceeds 64 GiB or 4096 files")
    included_translation = [item["path"] for item in files if item["path"] in TRANSLATION_FILES]
    if included_translation and "补丁/" in excluded:
        excluded.remove("补丁/")
    return {"source": str(source), "gameId": GAME_ID, "launcher": launcher,
            "files": files, "fileCount": len(files), "totalBytes": total,
            "requiresZip64": total >= zipfile.ZIP64_LIMIT or any(item["size"] >= zipfile.ZIP64_LIMIT for item in files),
            "compression": "ZIP_STORED", "excluded": excluded,
            "partiallyIncludedDirectories": ["补丁/"] if included_translation else [],
            "translationAllowlist": sorted(TRANSLATION_FILES)}


def package_game(source: str | Path, output: str | Path, *, include_saves=True, overwrite=False, progress=None, launcher=LAUNCHER) -> dict:
    plan = inspect_source(source, include_saves=include_saves, launcher=launcher)
    source = Path(plan["source"])
    output = Path(output).resolve()
    if output == source or source in output.parents:
        raise ArchiveError("Archive output must be outside the original game directory")
    if output.exists() and not overwrite:
        raise ArchiveError(f"Output already exists; use --overwrite explicitly: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest = {"schema": 1, "gameId": GAME_ID, "launcher": launcher, "files": [], "totalBytes": plan["totalBytes"]}
    descriptor, temporary = tempfile.mkstemp(prefix=output.name + ".", suffix=".partial", dir=output.parent)
    os.close(descriptor)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            done = 0
            for item in plan["files"]:
                path = source.joinpath(*item["path"].split("/"))
                before = _regular_file(path)
                info = zipfile.ZipInfo(item["path"])
                info.file_size = item["size"]
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                digest = hashlib.sha256()
                actual = 0
                with path.open("rb") as reader, archive.open(info, "w", force_zip64=True) as writer:
                    while chunk := reader.read(CHUNK):
                        writer.write(chunk)
                        digest.update(chunk)
                        actual += len(chunk)
                        done += len(chunk)
                        if progress:
                            progress(done, plan["totalBytes"])
                after = path.stat()
                if actual != item["size"] or (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
                    raise ArchiveError(f"Source changed during packaging: {item['path']}")
                manifest["files"].append({**item, "sha256": digest.hexdigest()})
            encoded = manifest_bytes(manifest)
            parse_manifest(encoded)
            archive.writestr(MANIFEST, encoded)
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return {"archive": str(output), "archiveBytes": output.stat().st_size, **manifest}


def read_archive_manifest(archive: zipfile.ZipFile) -> dict:
    names = set()
    for entry in archive.infolist():
        if entry.is_dir():
            raise ArchiveError("Explicit directory entries are unsupported")
        validate_path(entry.filename, allow_manifest=True)
        key = collision_key(entry.filename)
        if key in names:
            raise ArchiveError(f"Duplicate archive entry: {entry.filename!r}")
        names.add(key)
        mode = entry.external_attr >> 16
        if mode and stat.S_IFMT(mode) not in (0, stat.S_IFREG):
            raise ArchiveError(f"Non-regular archive entry: {entry.filename!r}")
        if entry.flag_bits & 1 or entry.compress_type != zipfile.ZIP_STORED:
            raise ArchiveError("Archive must use unencrypted ZIP_STORED entries")
    try:
        entry = archive.getinfo(MANIFEST)
    except KeyError as exc:
        raise ArchiveError("Archive manifest is missing") from exc
    if entry.file_size > MAX_MANIFEST:
        raise ArchiveError("Manifest exceeds 1 MiB")
    manifest = parse_manifest(archive.read(entry))
    expected = {item["path"] for item in manifest["files"]} | {MANIFEST}
    if expected != {entry.filename for entry in archive.infolist()}:
        raise ArchiveError("Archive entries do not match manifest exactly")
    for item in manifest["files"]:
        if archive.getinfo(item["path"]).file_size != item["size"]:
            raise ArchiveError(f"Archive file size differs from manifest: {item['path']!r}")
    return manifest


def verify_archive(path: str | Path, *, crc=False, progress=None) -> dict:
    with zipfile.ZipFile(path, "r", allowZip64=True) as archive:
        manifest = read_archive_manifest(archive)
        done = 0
        for item in manifest["files"]:
            digest = hashlib.sha256()
            with archive.open(item["path"]) as reader:
                while chunk := reader.read(CHUNK):
                    digest.update(chunk)
                    done += len(chunk)
                    if progress:
                        progress(done, manifest["totalBytes"])
            if digest.hexdigest() != item["sha256"]:
                raise ArchiveError(f"SHA-256 mismatch: {item['path']!r}")
        if crc:
            bad = archive.testzip()
            if bad:
                raise ArchiveError(f"Archive CRC mismatch: {bad!r}")
    return {"archive": str(Path(path).resolve()), "verified": True, "sha256Verified": True,
            "crcVerified": True, "extraCrcSweep": crc, "fileCount": len(manifest["files"]),
            "totalBytes": manifest["totalBytes"]}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    inspect = commands.add_parser("inspect", help="Print the safe packaging plan without reading game content")
    inspect.add_argument("source", type=Path)
    inspect.add_argument("--no-saves", action="store_true")
    inspect.add_argument("--launcher", default=LAUNCHER, help="Allowed root or nested translation EXE path")
    package = commands.add_parser("package", help="Write a ZIP64-capable ZIP_STORED game archive")
    package.add_argument("source", type=Path)
    package.add_argument("output", type=Path)
    package.add_argument("--dry-run", action="store_true", help="Print the plan without creating an archive")
    package.add_argument("--no-saves", action="store_true")
    package.add_argument("--overwrite", action="store_true")
    package.add_argument("--launcher", default=LAUNCHER, help="Allowed root or nested translation EXE path")
    verify = commands.add_parser("verify", help="Validate paths, manifest, sizes, SHA-256, and CRC")
    verify.add_argument("archive", type=Path)
    verify.add_argument("--crc", action="store_true", help="Perform an additional full CRC-only archive sweep")
    args = parser.parse_args(argv)
    try:
        if args.command == "inspect" or (args.command == "package" and args.dry_run):
            result = inspect_source(args.source, include_saves=not args.no_saves, launcher=args.launcher)
            if args.command == "package":
                result["output"] = str(args.output.resolve())
                result["dryRun"] = True
        elif args.command == "package":
            def progress(done, total):
                percent = done * 100 // max(total, 1)
                if percent != getattr(progress, "last", -1) and percent % 5 == 0:
                    progress.last = percent
                    print(f"Packaging {percent}% ({done:,}/{total:,} bytes)", file=sys.stderr, flush=True)
            result = package_game(args.source, args.output, include_saves=not args.no_saves,
                                  overwrite=args.overwrite, progress=progress, launcher=args.launcher)
        else:
            result = verify_archive(args.archive, crc=args.crc)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except (ArchiveError, OSError, zipfile.BadZipFile, RuntimeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
