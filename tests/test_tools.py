"""Security, integrity, and ZIP64 regression checks using tiny synthetic files."""
import contextlib
import io
import json
from pathlib import Path
import shlex
import struct
import sys
import tempfile
import tarfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import adb_deploy
import game_archive as archive
import make_pc_save_backup as save_backup


class GameArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "game"
        self.source.mkdir()
        for name in ("script.noa", "psb.noa", "ststeady_patch.exe", "ststeady.exe", "emotedriver.dll", "hook.dll", "AymKze.txt"):
            (self.source / name).write_bytes((name + " content").encode())
        self.output = self.root / "game.zip"

    def tearDown(self):
        self.temporary.cleanup()

    def test_unicode_savedata_roundtrip_and_excluded_patch_utilities(self):
        saves = self.source / "savedata" / "玩家"
        saves.mkdir(parents=True)
        (saves / "存档01.dat").write_bytes(b"portable save content")
        (saves / "1").write_bytes(b"extensionless save")
        (saves / "run.exe").write_bytes(b"not a save")
        patches = self.source / "补丁"
        patches.mkdir()
        (patches / "install.exe").write_bytes(b"installer")
        for name in ("EntisGLS_DVD_PATCH.exe", "EntisGLS_DVD_PATCH.dll", "uninst.exe", "start.bat"):
            (self.source / name).write_bytes(b"excluded")
        plan = archive.inspect_source(self.source)
        packaged = archive.package_game(self.source, self.output)
        result = archive.verify_archive(self.output, crc=True)
        self.assertTrue(result["verified"])
        self.assertIn("savedata/玩家/存档01.dat", {item["path"] for item in packaged["files"]})
        self.assertIn("savedata/玩家/1", {item["path"] for item in packaged["files"]})
        self.assertEqual(len(packaged["files"]), 9)
        self.assertIn("EntisGLS_DVD_PATCH.exe", plan["excluded"])
        self.assertIn("savedata/玩家/run.exe", plan["excluded"])
        with zipfile.ZipFile(self.output) as reader:
            self.assertTrue(all(item.compress_type == zipfile.ZIP_STORED for item in reader.infolist()))

    def test_only_exact_nested_translation_files_and_selected_launcher_are_allowed(self):
        translation = self.source / "补丁" / "汉化补丁"
        translation.mkdir(parents=True)
        for name in ("ststeady_patch.exe", "hook.dll", "patchcn.noa", "setup.exe", "EntisGLS_DVD_PATCH.dll"):
            (translation / name).write_bytes(name.encode())
        other = self.source / "补丁" / "other"
        other.mkdir()
        (other / "ststeady_patch.exe").write_bytes(b"must stay excluded")
        launcher = "补丁/汉化补丁/ststeady_patch.exe"
        plan = archive.inspect_source(self.source, launcher=launcher)
        approved = {item["path"] for item in plan["files"] if item["path"].startswith("补丁/")}
        self.assertEqual(approved, archive.TRANSLATION_FILES)
        packaged = archive.package_game(self.source, self.output, launcher=launcher)
        self.assertEqual(packaged["launcher"], launcher)
        archive.verify_archive(self.output)
        for name in ("补丁/汉化补丁/setup.exe", "补丁/other/ststeady_patch.exe", "补丁/汉化补丁/EntisGLS_DVD_PATCH.dll"):
            with self.assertRaises(archive.ArchiveError):
                archive.validate_path(name)
        with self.assertRaises(archive.ArchiveError):
            archive.validate_launcher("savedata/game.exe")

    def test_reject_traversal_and_unsafe_paths(self):
        bad_paths = ("../psb.noa", "/psb.noa", "C:/psb.noa", "savedata/../game.dat",
                     "savedata\\game.dat", "savedata//x.dat", "savedata/CON.dat",
                     "savedata/a. /x.dat", "savedata/x.dat\x00", "savedata/x.apk", "other.dat")
        for path in bad_paths:
            with self.subTest(path=path), self.assertRaises(archive.ArchiveError):
                archive.validate_path(path)
        archive.validate_path("savedata/中文.dat")
        archive.validate_path("背景.noa")

    def test_manifest_rejects_duplicates_wrong_sizes_and_duplicate_keys(self):
        result = archive.package_game(self.source, self.output)
        manifest = {key: result[key] for key in ("schema", "gameId", "launcher", "files", "totalBytes")}
        duplicate = json.loads(json.dumps(manifest))
        duplicate["files"].append(duplicate["files"][0])
        with self.assertRaises(archive.ArchiveError):
            archive.parse_manifest(archive.manifest_bytes(duplicate))
        duplicate["files"] = manifest["files"] + [{"path": "SCRIPT.noa", "size": 1, "sha256": "0" * 64}]
        with self.assertRaises(archive.ArchiveError):
            archive.parse_manifest(archive.manifest_bytes(duplicate))
        manifest["totalBytes"] += 1
        with self.assertRaises(archive.ArchiveError):
            archive.parse_manifest(archive.manifest_bytes(manifest))
        with self.assertRaises(archive.ArchiveError):
            archive.parse_manifest(b'{"schema":1,"schema":1}')

    def test_hash_tamper_with_valid_crc_is_rejected(self):
        archive.package_game(self.source, self.output)
        tampered = self.root / "tampered.zip"
        with zipfile.ZipFile(self.output) as original, zipfile.ZipFile(tampered, "w", compression=zipfile.ZIP_STORED) as writer:
            for item in original.infolist():
                data = original.read(item)
                if item.filename == "script.noa":
                    data = bytes((data[0] ^ 1,)) + data[1:]
                writer.writestr(item, data)
        with self.assertRaisesRegex(archive.ArchiveError, "SHA-256 mismatch"):
            archive.verify_archive(tampered)

    def test_crc_tamper_is_rejected(self):
        archive.package_game(self.source, self.output)
        with zipfile.ZipFile(self.output) as reader:
            item = reader.getinfo("script.noa")
            header_offset = item.header_offset
        with self.output.open("r+b") as writer:
            writer.seek(header_offset)
            header = writer.read(30)
            name_length, extra_length = struct.unpack_from("<HH", header, 26)
            writer.seek(header_offset + 30 + name_length + extra_length)
            first = writer.read(1)
            writer.seek(-1, 1)
            writer.write(bytes((first[0] ^ 1,)))
        with self.assertRaises(zipfile.BadZipFile):
            archive.verify_archive(self.output)

    def test_unknown_extra_archive_entry_is_rejected(self):
        archive.package_game(self.source, self.output)
        with zipfile.ZipFile(self.output, "a") as writer:
            writer.writestr("install.exe", b"bad")
        with self.assertRaises(archive.ArchiveError):
            archive.verify_archive(self.output)

    def test_zip64_local_headers_and_large_psb_plan_without_large_allocation(self):
        # Lower ZIP64 threshold to exercise the actual writer's ZIP64 paths with tiny fixtures.
        with patch.object(zipfile, "ZIP64_LIMIT", 8):
            archive.package_game(self.source, self.output)
            archive.verify_archive(self.output)
        data = self.output.read_bytes()
        self.assertIn(b"PK\x06\x06", data)  # ZIP64 end-of-central-directory record
        original = archive._regular_file
        def fake_large(path):
            info = original(path)
            return SimpleNamespace(st_size=5 * 1024**3 if path.name == "psb.noa" else info.st_size)
        with patch.object(archive, "_regular_file", side_effect=fake_large):
            plan = archive.inspect_source(self.source)
        self.assertTrue(plan["requiresZip64"])
        self.assertEqual(next(item["size"] for item in plan["files"] if item["path"] == "psb.noa"), 5 * 1024**3)

    def test_dry_run_never_creates_output_and_never_hashes(self):
        with contextlib.redirect_stdout(io.StringIO()) as output, patch.object(archive.hashlib, "sha256", side_effect=AssertionError("must not hash")):
            self.assertEqual(archive.main(["package", str(self.source), str(self.output), "--dry-run"]), 0)
        self.assertFalse(self.output.exists())
        self.assertTrue(json.loads(output.getvalue())["dryRun"])

    def test_refuse_output_inside_original_and_existing_output(self):
        with self.assertRaises(archive.ArchiveError):
            archive.package_game(self.source, self.source / "output.zip")
        self.output.write_bytes(b"keep me")
        with self.assertRaises(archive.ArchiveError):
            archive.package_game(self.source, self.output)
        self.assertEqual(self.output.read_bytes(), b"keep me")

    def test_direct_plan_and_insufficient_space_do_not_mutate_device(self):
        packaged = archive.package_game(self.source, self.output)
        manifest = adb_deploy.direct_manifest(self.source, self.output)
        self.assertEqual(manifest["totalBytes"], packaged["totalBytes"])
        with patch.object(adb_deploy, "free_bytes", return_value=0), patch.object(adb_deploy.subprocess, "Popen") as popen:
            with self.assertRaisesRegex(archive.ArchiveError, "Device needs"):
                adb_deploy.stream_direct("adb", "serial", self.source, manifest, 1)
        popen.assert_not_called()

    def test_adb_select_requires_explicit_serial_for_multiple_devices(self):
        result = SimpleNamespace(stdout="List of devices attached\na device\nb device\nc unauthorized\n")
        with patch.object(adb_deploy.subprocess, "run", return_value=result):
            with self.assertRaises(archive.ArchiveError):
                adb_deploy.select_serial("adb", None)
            self.assertEqual(adb_deploy.select_serial("adb", "b"), "b")
            with self.assertRaises(archive.ArchiveError):
                adb_deploy.select_serial("adb", "c")
        for value in (0, -1, "1; rm -rf /", True):
            with self.assertRaises(archive.ArchiveError):
                adb_deploy.container_paths(value)

    def test_raw_shell_command_is_quoted_once_and_reports_remote_status(self):
        command = "test -d files/rootfs && printf 'STUDY_IMPORT_READY\\n' && tar -xf -"
        argv = adb_deploy.raw_shell_argv("adb", "serial", command)
        self.assertEqual(argv[3:5], ["shell", "-T"])
        self.assertEqual(shlex.split(argv[5]), ["run-as", "com.winlator", "sh", "-c", command])
        self.assertNotIn("exec-in", argv)

    def test_binary_probe_checks_all_byte_values_and_removes_only_generated_file(self):
        calls = []
        def remote(argv, **kwargs):
            calls.append(argv)
            if argv[3] == "exec-in":
                self.assertEqual(set(kwargs["input"]), set(range(256)))
                self.assertEqual(argv[4:7], ["run-as", "com.winlator", "dd"])
                self.assertEqual(argv[-2:], ["bs=4096", "count=1"])
                digest = archive.hashlib.sha256(kwargs["input"]).hexdigest().encode()
                remote.digest = digest
                remote.path = argv[-3].removeprefix("of=")
                self.assertRegex(remote.path, r"^cache/study-stream-probe-[0-9a-f]{32}\.bin$")
                return SimpleNamespace(stdout=b"")
            program = shlex.split(argv[-1])[-1]
            if program.startswith("sha256sum "):
                self.assertEqual(program, "sha256sum " + remote.path)
                return SimpleNamespace(stdout=remote.digest + b"  " + remote.path.encode() + b"\n")
            self.assertEqual(program, "rm -f " + remote.path)
            return SimpleNamespace(stdout=b"")
        with patch.object(adb_deploy.subprocess, "run", side_effect=remote):
            result = adb_deploy.probe_stream("adb", "serial")
        self.assertTrue(result["binaryPipeVerified"])
        self.assertTrue(result["temporaryProbeRemoved"])
        self.assertEqual(len(calls), 3)

    def test_failed_remote_start_sends_no_game_bytes(self):
        packaged = archive.package_game(self.source, self.output)
        manifest = {key: packaged[key] for key in ("schema", "gameId", "launcher", "files", "totalBytes")}
        with patch.object(adb_deploy, "free_bytes", return_value=100 * 1024**3), \
             patch.object(adb_deploy, "probe_stream", return_value={"binaryPipeVerified": True}), \
             patch.object(adb_deploy.subprocess, "run", return_value=SimpleNamespace(returncode=3, stdout=b"", stderr=b"missing drive_c")), \
             patch.object(adb_deploy.subprocess, "Popen") as popen, \
             patch.object(adb_deploy, "_regular_file", side_effect=AssertionError("must not read files before ready")):
            with self.assertRaisesRegex(archive.ArchiveError, "staging failed before transfer"):
                adb_deploy.stream_direct("adb", "serial", self.source, manifest, 1)
        popen.assert_not_called()

    def test_direct_transfer_uses_raw_exec_args_and_manifest_is_final_tar_member(self):
        packaged = archive.package_game(self.source, self.output)
        manifest = {key: packaged[key] for key in ("schema", "gameId", "launcher", "files", "totalBytes")}
        class RecordingPipe(io.BytesIO):
            def close(self):
                self.recorded = self.getvalue()
                super().close()
        pipe = RecordingPipe()
        process = SimpleNamespace(stdin=pipe, poll=lambda: None, wait=lambda **kwargs: 0,
                                  terminate=lambda: None)
        with patch.object(adb_deploy, "free_bytes", return_value=100 * 1024**3), \
             patch.object(adb_deploy, "probe_stream"), \
             patch.object(adb_deploy, "prepare_staging"), \
             patch.object(adb_deploy, "check_final_manifest"), \
             patch.object(adb_deploy.subprocess, "Popen", return_value=process) as popen:
            result = adb_deploy.stream_direct("adb", "serial", self.source, manifest, 1)
        self.assertEqual(popen.call_args.args[0], ["adb", "-s", "serial", "exec-in", "run-as", "com.winlator",
                         "tar", "-xf", "-", "-C", "files/rootfs/home/xuser-1/.wine/drive_c/.StudySteady-import"])
        with tarfile.open(fileobj=io.BytesIO(pipe.recorded), mode="r:") as reader:
            self.assertEqual(reader.getnames()[-1], archive.MANIFEST)
            for item in manifest["files"]:
                self.assertEqual(reader.extractfile(item["path"]).read(), (self.source / item["path"]).read_bytes())
        self.assertEqual(result["totalBytes"], manifest["totalBytes"])

    def test_pc_save_backup_preserves_opaque_original_bytes_and_unicode_destination(self):
        saves = self.root / "pc-saves"
        saves.mkdir()
        original = bytes(range(256)) * 3
        (saves / "sysenv.dat").write_bytes(original)
        (saves / "save0001.bmp").write_bytes(b"opaque thumbnail")
        output = self.root / "pc-save.zip"
        result = save_backup.build_backup(saves, output)
        self.assertTrue(result["verified"])
        self.assertFalse(result["sourceModified"])
        self.assertEqual((saves / "sysenv.dat").read_bytes(), original)
        with zipfile.ZipFile(output) as reader:
            expected = save_backup.DESTINATION + "sysenv.dat"
            self.assertEqual(reader.read(expected), original)
            manifest = save_backup.validate_manifest(reader.read(save_backup.SAVE_MANIFEST))
            self.assertEqual(manifest["kind"], "save-backup")
        with self.assertRaises(archive.ArchiveError):
            save_backup.validate_save_path(save_backup.DESTINATION + "../sysenv.dat")
        with self.assertRaises(archive.ArchiveError):
            save_backup.build_backup(saves, saves / "bad.zip")

    def test_pc_save_backup_tamper_is_rejected(self):
        saves = self.root / "pc-saves"
        saves.mkdir()
        (saves / "sysenv.dat").write_bytes(b"opaque progress")
        output = self.root / "pc-save.zip"
        save_backup.build_backup(saves, output)
        corrupt = self.root / "bad-save.zip"
        with zipfile.ZipFile(output) as reader, zipfile.ZipFile(corrupt, "w", compression=zipfile.ZIP_STORED) as writer:
            for entry in reader.infolist():
                data = reader.read(entry)
                if entry.filename.endswith("sysenv.dat"):
                    data = data[:-1] + b"!"
                writer.writestr(entry, data)
        with self.assertRaisesRegex(archive.ArchiveError, "SHA-256 mismatch"):
            save_backup.verify_backup(corrupt)


if __name__ == "__main__":
    unittest.main()
