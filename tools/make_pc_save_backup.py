#!/usr/bin/env python3
"""Back up the user's unmodified PC progress for StudyArchive.restore()."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import zipfile

from game_archive import (ArchiveError, CHUNK, GAME_ID, MAX_BYTES, MAX_MANIFEST,
                          UNSAFE_SAVE_SUFFIXES, _integer, _regular_file,
                          collision_key, manifest_bytes, validate_relative_path)

SAVE_MANIFEST = "save-manifest.json"
PC_DIRECTORY = Path("ま～まれぇど") / "スタディ§ステディv1.01"
DESTINATION = ".wine/drive_c/users/xuser/AppData/Roaming/ま～まれぇど/スタディ§ステディv1.01/"


def default_source() -> Path:
    roaming = os.environ.get("APPDATA")
    if not roaming:
        raise ArchiveError("APPDATA is unavailable; specify --source")
    return Path(roaming) / PC_DIRECTORY


def validate_save_path(path: str) -> str:
    validate_relative_path(path)
    if not path.startswith(DESTINATION) or len(path) <= len(DESTINATION):
        raise ArchiveError("PC progress must remain under the exact original AppData game directory")
    if Path(path).suffix.lower() in UNSAFE_SAVE_SUFFIXES:
        raise ArchiveError("Executable/script files are not PC progress")
    return path


def inspect_saves(source: str | Path) -> dict:
    source = Path(source).resolve(strict=True)
    if not source.is_dir():
        raise ArchiveError("PC save source is not a directory")
    files = []
    seen = set()
    for current, directories, names in os.walk(source, followlinks=False):
        directories.sort()
        for name in directories:
            candidate = Path(current) / name
            info = candidate.lstat()
            if candidate.is_symlink() or getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0):
                raise ArchiveError("Save directories must not be symlinks/reparse points")
        for name in sorted(names):
            candidate = Path(current) / name
            relative = candidate.relative_to(source).as_posix()
            destination = validate_save_path(DESTINATION + relative)
            key = collision_key(destination)
            if key in seen:
                raise ArchiveError("Save files contain a case/Unicode path collision")
            seen.add(key)
            info = _regular_file(candidate)
            files.append({"path": destination, "sourceRelative": relative, "size": info.st_size})
    files.sort(key=lambda item: item["path"])
    total = sum(item["size"] for item in files)
    if not files or len(files) > 4096 or total > MAX_BYTES:
        raise ArchiveError("PC progress must contain 1..4096 files and at most 64 GiB")
    return {"source": str(source), "destination": DESTINATION, "files": files,
            "fileCount": len(files), "totalBytes": total, "readOnlySource": True}


def validate_manifest(raw: bytes) -> dict:
    if len(raw) > MAX_MANIFEST:
        raise ArchiveError("Save manifest exceeds 1 MiB")
    try:
        def pairs(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise ArchiveError("Duplicate save manifest JSON key")
                result[key] = value
            return result
        manifest = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ArchiveError("Invalid save manifest JSON") from exc
    if not isinstance(manifest, dict) or type(manifest.get("schema")) is not int or manifest["schema"] != 1:
        raise ArchiveError("Invalid save backup schema")
    if manifest.get("gameId") != GAME_ID or manifest.get("kind") != "save-backup":
        raise ArchiveError("Unsupported save backup kind/gameId")
    entries = manifest.get("files")
    if not isinstance(entries, list) or not 1 <= len(entries) <= 4096:
        raise ArchiveError("Invalid save backup file list")
    seen = set()
    total = 0
    import re
    for item in entries:
        if not isinstance(item, dict):
            raise ArchiveError("Save manifest entries must be objects")
        path = validate_save_path(item.get("path"))
        key = collision_key(path)
        if key in seen:
            raise ArchiveError("Duplicate save backup filename")
        seen.add(key)
        total += _integer(item.get("size"), "save file size")
        if not isinstance(item.get("sha256"), str) or not re.fullmatch("[0-9a-f]{64}", item["sha256"]):
            raise ArchiveError("Invalid save file SHA-256")
    if total > MAX_BYTES or total != _integer(manifest.get("totalBytes"), "save totalBytes"):
        raise ArchiveError("Save totalBytes does not match file list")
    return manifest


def build_backup(source: str | Path, output: str | Path, *, overwrite=False) -> dict:
    plan = inspect_saves(source)
    source = Path(plan["source"])
    output = Path(output).resolve()
    if source == output or source in output.parents:
        raise ArchiveError("Backup output must be outside the original PC save directory")
    if output.exists() and not overwrite:
        raise ArchiveError("Backup already exists; use --overwrite explicitly")
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=output.name + ".", suffix=".partial", dir=output.parent)
    os.close(descriptor)
    manifest = {"schema": 1, "gameId": GAME_ID, "kind": "save-backup",
                "files": [], "totalBytes": plan["totalBytes"]}
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as writer:
            for item in plan["files"]:
                path = source.joinpath(*item["sourceRelative"].split("/"))
                before = _regular_file(path)
                digest = hashlib.sha256()
                count = 0
                info = zipfile.ZipInfo(item["path"])
                info.file_size = item["size"]
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                with path.open("rb") as reader, writer.open(info, "w", force_zip64=True) as target:
                    while chunk := reader.read(CHUNK):
                        target.write(chunk)
                        digest.update(chunk)
                        count += len(chunk)
                after = path.stat()
                if count != item["size"] or (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
                    raise ArchiveError("Original PC save changed during backup")
                manifest["files"].append({"path": item["path"], "size": count, "sha256": digest.hexdigest()})
            encoded = manifest_bytes(manifest)
            validate_manifest(encoded)
            writer.writestr(SAVE_MANIFEST, encoded)
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    verified = verify_backup(output)
    return {"archive": str(output), "archiveBytes": output.stat().st_size,
            "fileCount": len(manifest["files"]), "totalBytes": manifest["totalBytes"],
            "verified": verified["verified"], "source": str(source), "sourceModified": False}


def verify_backup(path: str | Path) -> dict:
    with zipfile.ZipFile(path, allowZip64=True) as reader:
        names = set()
        for entry in reader.infolist():
            if entry.filename != SAVE_MANIFEST:
                validate_save_path(entry.filename)
            key = collision_key(entry.filename)
            if key in names or entry.is_dir() or entry.flag_bits & 1 or entry.compress_type != zipfile.ZIP_STORED:
                raise ArchiveError("Unsafe or duplicate save ZIP entry")
            mode = entry.external_attr >> 16
            if mode and stat.S_IFMT(mode) not in (0, stat.S_IFREG):
                raise ArchiveError("Save ZIP entries must be regular files")
            names.add(key)
        try:
            manifest_entry = reader.getinfo(SAVE_MANIFEST)
        except KeyError as exc:
            raise ArchiveError("Save manifest missing") from exc
        if manifest_entry.file_size > MAX_MANIFEST:
            raise ArchiveError("Save manifest exceeds 1 MiB")
        manifest = validate_manifest(reader.read(manifest_entry))
        expected = {item["path"] for item in manifest["files"]} | {SAVE_MANIFEST}
        if expected != set(reader.namelist()):
            raise ArchiveError("Save ZIP entries do not exactly match the manifest")
        for item in manifest["files"]:
            if reader.getinfo(item["path"]).file_size != item["size"]:
                raise ArchiveError("Save file size differs from manifest")
            digest = hashlib.sha256()
            with reader.open(item["path"]) as stream:
                while chunk := stream.read(CHUNK):
                    digest.update(chunk)
            if digest.hexdigest() != item["sha256"]:
                raise ArchiveError("Save file SHA-256 mismatch")
    return {"archive": str(Path(path).resolve()), "verified": True, "sha256Verified": True,
            "crcVerified": True, "fileCount": len(manifest["files"]), "totalBytes": manifest["totalBytes"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="PC AppData save directory; default is the original game's directory")
    parser.add_argument("--output", type=Path, default=Path("dist/PC-save-backup.zip"))
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--verify", type=Path, help="Verify an existing backup instead of generating one")
    args = parser.parse_args(argv)
    try:
        if args.verify:
            result = verify_backup(args.verify)
        else:
            source = args.source or default_source()
            result = inspect_saves(source) if args.dry_run else build_backup(source, args.output, overwrite=args.overwrite)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except (ArchiveError, OSError, zipfile.BadZipFile, RuntimeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
