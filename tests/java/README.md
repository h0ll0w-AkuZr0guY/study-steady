# StudyArchive JVM integration checks

Run from the project directory with Python 3.10+ and a JDK on `PATH` or in `JAVA_HOME`:

```powershell
python tools/run_archive_tests.py
```

The runner downloads the pinned `org.json:json:20250517` dependency from Maven Central,
verifies its SHA-256, then caches it. For an offline run, supply that same JAR:

```powershell
python tools/run_archive_tests.py --json-jar C:\path\json-20250517.jar
```

All 19 checks use tiny synthetic assets in a fresh temporary directory. No installed
game, connected phone, or real save file is read or changed. Fixtures and compiled
classes are removed when the runner exits.

Coverage includes complete ZIP import and USB staging commit, refusal to replace an
existing game, failed-import cleanup, preservation of failed USB staging, corrupted
hashes, ZIP traversal, case collisions, backup file selection, backup/restore round
trip, retention of displaced original saves, rollback after a partial commit, denied
restore of an executable outside the save scope, and preservation of 64-bit sizes.

These host checks use the real helper source with the JVM `org.json` implementation.
They complement Android APK compilation; they do not establish device runtime,
filesystem symlink handling, real multi-gigabyte ZIP64 transfer, or game compatibility.
