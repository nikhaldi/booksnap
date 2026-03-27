"""Host-side emulator daemon for sandboxed agent sessions.

Runs on the host machine and exposes a small HTTP API that the agent
container can call to interact with the Android emulator.

Usage:
    python emulator_daemon.py --port 9100 --data-dir ./data --pipeline-dir ../src/main/java/com/booksnap/pipeline
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from booksnap_labs.daemon_base import DaemonBase

_DEVICE_BASE_DIR = "/data/local/tmp/booksnap"
_TEST_PACKAGE = "dev.booksnap.harness.test"
_TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
_TEST_CLASS = "dev.booksnap.harness.OcrBenchmarkTest"

_LAB_DIR = Path(__file__).resolve().parent
_TEST_HARNESS_DIR = _LAB_DIR.parent / "test-harness"
_PIPELINE_DEST = _TEST_HARNESS_DIR / "app" / "src" / "pipeline" / "java"


def _find_adb() -> str:
    android_home = os.environ.get("ANDROID_HOME", "")
    if android_home:
        adb = os.path.join(android_home, "platform-tools", "adb")
        if os.path.isfile(adb):
            return adb
    adb = shutil.which("adb")
    if adb:
        return adb
    raise RuntimeError("adb not found")


ADB = _find_adb()


def _run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def _adb(*args: str) -> subprocess.CompletedProcess:
    return _run([ADB, *args])


class EmulatorDaemon(DaemonBase):
    def __init__(self):
        super().__init__(description="BookSnap Android emulator daemon", default_port=9100)

    def register_handlers(self) -> None:
        self.register("check_device", self._check_device)
        self.register("build_apks", self._build_apks)
        self.register("push_files", self._push_files)
        self.register("run_tests", self._run_tests)
        self.register("pull_results", self._pull_results)

    def _check_device(self, request: dict) -> dict:
        result = _adb("devices")
        lines = [
            line
            for line in result.stdout.strip().splitlines()[1:]
            if line.strip() and "device" in line
        ]
        connected = len(lines) > 0
        if connected:
            _adb("shell", "svc", "wifi", "disable")
            _adb("shell", "svc", "data", "disable")
        return {"connected": connected, "devices": lines}

    def _build_apks(self, request: dict) -> dict:
        err = self.copy_pipeline(
            _PIPELINE_DEST,
            ignore_patterns=("*.gradle", "*.gradle.kts", "*.json", "*.md"),
        )
        if err is not None:
            return err

        gradlew = _TEST_HARNESS_DIR / "gradlew"
        if not gradlew.exists():
            return {"ok": False, "error": "gradlew not found in test-harness"}

        result = _run(
            [str(gradlew), "assembleDebug", "assembleDebugAndroidTest"],
            cwd=_TEST_HARNESS_DIR,
        )
        if result.returncode != 0:
            return {"ok": False, "error": result.stderr[-2000:]}

        app_apk = _TEST_HARNESS_DIR / "app/build/outputs/apk/debug/app-debug.apk"
        test_apk = (
            _TEST_HARNESS_DIR
            / "app/build/outputs/apk/androidTest/debug"
            / "app-debug-androidTest.apk"
        )

        for apk in [app_apk, test_apk]:
            if not apk.exists():
                return {"ok": False, "error": f"APK not found: {apk}"}
            r = _adb("install", "-r", str(apk))
            if r.returncode != 0:
                return {"ok": False, "error": f"Install failed: {r.stderr}"}

        return {"ok": True}

    def _push_files(self, request: dict) -> dict:
        manifest = request.get("manifest", {})
        if self.data_dir is None:
            return {"ok": False, "error": "No data directory configured"}

        _adb("shell", "rm", "-rf", _DEVICE_BASE_DIR)
        _adb("shell", "mkdir", "-p", _DEVICE_BASE_DIR)

        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
            json.dump(manifest, f)
            tmp = f.name

        try:
            _adb("push", tmp, f"{_DEVICE_BASE_DIR}/manifest.json")
        finally:
            os.unlink(tmp)

        pushed = 0
        errors = []
        pushed_dirs: set[str] = set()

        for img in manifest.get("images", []):
            src = self.data_dir / img["path"]
            if not src.exists():
                errors.append(f"Not found: {img['path']}")
                continue

            try:
                src.resolve().relative_to(self.data_dir.resolve())
            except ValueError:
                errors.append(f"Path escape attempt: {img['path']}")
                continue

            parent = str(Path(img["path"]).parent)
            if parent != "." and parent not in pushed_dirs:
                _adb("shell", "mkdir", "-p", f"{_DEVICE_BASE_DIR}/{parent}")
                pushed_dirs.add(parent)

            _adb("push", str(src), f"{_DEVICE_BASE_DIR}/{img['path']}")
            pushed += 1

        return {"ok": True, "pushed": pushed, "errors": errors}

    def _run_tests(self, request: dict) -> dict:
        _adb(
            "shell", "run-as", "dev.booksnap.harness",
            "rm", "-f", "files/results.json",
        )

        cmd = [
            "shell", "am", "instrument", "-w",
            "-e", "class", _TEST_CLASS,
        ]
        spell_check = os.environ.get("BOOKSNAP_SPELL_CHECK")
        if spell_check is not None:
            cmd.extend(["-e", "spellCheck", spell_check])
        cmd.append(f"{_TEST_PACKAGE}/{_TEST_RUNNER}")

        result = _adb(*cmd)
        return {
            "ok": result.returncode == 0 and "FAILURES" not in result.stdout,
            "stdout": result.stdout[-4000:],
        }

    def _pull_results(self, request: dict) -> dict:
        r = _adb(
            "shell", "run-as", "dev.booksnap.harness",
            "cat", "files/results.json",
        )
        if r.returncode != 0:
            return {"ok": False, "error": f"Failed to read results: {r.stderr}"}

        try:
            data = json.loads(r.stdout)
            return {"ok": True, "data": data}
        except json.JSONDecodeError as e:
            return {"ok": False, "error": f"Invalid JSON: {e}"}


if __name__ == "__main__":
    EmulatorDaemon().run()
