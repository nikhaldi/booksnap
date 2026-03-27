"""Host-side iOS Simulator daemon for sandboxed agent sessions.

Runs on the host machine and exposes a small HTTP API that the agent
container can call to interact with the iOS Simulator.

Usage:
    python simulator_daemon.py --port 9200 --data-dir ./data --pipeline-dir ../
"""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

from booksnap_labs.daemon_base import DaemonBase

_LAB_DIR = Path(__file__).resolve().parent
_TEST_HARNESS_DIR = _LAB_DIR.parent / "test-harness"
_PIPELINE_DEST = _TEST_HARNESS_DIR / "BookSnapHarness" / "Pipeline"
_BUNDLE_ID = "dev.booksnap.harness"


def _run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def _xcrun(*args: str) -> subprocess.CompletedProcess:
    return _run(["xcrun", *args])


def _simctl(*args: str) -> subprocess.CompletedProcess:
    return _xcrun("simctl", *args)


def _get_booted_udid() -> str | None:
    result = _simctl("list", "devices", "booted", "-j")
    if result.returncode != 0:
        return None
    try:
        data = json.loads(result.stdout)
        for runtime_devices in data.get("devices", {}).values():
            for d in runtime_devices:
                if d.get("state") == "Booted":
                    return d["udid"]
    except (json.JSONDecodeError, KeyError):
        pass
    return None


def _get_app_container(udid: str) -> str | None:
    result = _simctl("get_app_container", udid, _BUNDLE_ID, "data")
    if result.returncode != 0:
        return None
    return result.stdout.strip()


class SimulatorDaemon(DaemonBase):
    def __init__(self):
        super().__init__(description="BookSnap iOS simulator daemon", default_port=9200)

    def register_handlers(self) -> None:
        self.register("check_device", self._check_device)
        self.register("build", self._build)
        self.register("push_files", self._push_files)
        self.register("run_tests", self._run_tests)
        self.register("pull_results", self._pull_results)

    def _check_device(self, request: dict) -> dict:
        result = _simctl("list", "devices", "booted", "-j")
        if result.returncode != 0:
            return {"connected": False, "error": result.stderr}
        try:
            data = json.loads(result.stdout)
            devices = []
            for runtime_devices in data.get("devices", {}).values():
                devices.extend(runtime_devices)
            booted = [d for d in devices if d.get("state") == "Booted"]
            if booted:
                return {
                    "connected": True,
                    "device": booted[0]["name"],
                    "udid": booted[0]["udid"],
                }
            return {"connected": False, "error": "No booted simulator found"}
        except (json.JSONDecodeError, KeyError) as e:
            return {"connected": False, "error": str(e)}

    def _build(self, request: dict) -> dict:
        err = self.copy_pipeline(
            _PIPELINE_DEST,
            ignore_patterns=("*.md", "*.json"),
        )
        if err is not None:
            return err

        udid = _get_booted_udid()
        if not udid:
            return {"ok": False, "error": "No booted simulator"}

        result = _run(
            [
                "xcodebuild",
                "build-for-testing",
                "-project",
                str(_TEST_HARNESS_DIR / "BookSnapHarness.xcodeproj"),
                "-scheme",
                "BookSnapHarness",
                "-destination",
                f"id={udid}",
                "-derivedDataPath",
                str(_TEST_HARNESS_DIR / "DerivedData"),
            ],
            cwd=_TEST_HARNESS_DIR,
        )

        if result.returncode != 0:
            return {
                "ok": False,
                "error": f"xcodebuild failed:\n{result.stderr[-2000:]}",
            }

        # Install the app on the simulator so its data container exists
        app_path = (
            _TEST_HARNESS_DIR
            / "DerivedData"
            / "Build"
            / "Products"
            / "Debug-iphonesimulator"
            / "BookSnapHarness.app"
        )
        if app_path.exists():
            install_result = _simctl("install", udid, str(app_path))
            if install_result.returncode != 0:
                return {
                    "ok": False,
                    "error": f"simctl install failed: {install_result.stderr}",
                }

        return {"ok": True}

    def _push_files(self, request: dict) -> dict:
        manifest = request.get("manifest", {})
        if self.data_dir is None:
            return {"ok": False, "error": "No data directory configured"}

        udid = _get_booted_udid()
        if not udid:
            return {"ok": False, "error": "No booted simulator"}

        container = _get_app_container(udid)
        if not container:
            return {
                "ok": False,
                "error": "Could not find app container. Is the app installed?",
            }

        test_data_dir = Path(container) / "Documents" / "booksnap"
        test_data_dir.mkdir(parents=True, exist_ok=True)

        with open(test_data_dir / "manifest.json", "w") as f:
            json.dump(manifest, f)

        pushed = 0
        errors = []
        for img in manifest.get("images", []):
            src = self.data_dir / img["path"]
            if not src.exists():
                errors.append(f"Missing: {src}")
                continue
            shutil.copy2(src, test_data_dir / img["path"])
            pushed += 1

        return {"ok": True, "pushed": pushed, "errors": errors}

    def _run_tests(self, request: dict) -> dict:
        udid = _get_booted_udid()
        if not udid:
            return {"ok": False, "error": "No booted simulator"}

        result = _run(
            [
                "xcodebuild",
                "test-without-building",
                "-project",
                str(_TEST_HARNESS_DIR / "BookSnapHarness.xcodeproj"),
                "-scheme",
                "BookSnapHarness",
                "-destination",
                f"id={udid}",
                "-derivedDataPath",
                str(_TEST_HARNESS_DIR / "DerivedData"),
            ],
            cwd=_TEST_HARNESS_DIR,
        )

        return {
            "ok": result.returncode == 0,
            "stdout": result.stdout[-4000:],
        }

    def _pull_results(self, request: dict) -> dict:
        udid = _get_booted_udid()
        if not udid:
            return {"ok": False, "error": "No booted simulator"}

        container = _get_app_container(udid)
        if not container:
            return {"ok": False, "error": "Could not find app container"}

        results_path = Path(container) / "Documents" / "booksnap" / "results.json"
        if not results_path.exists():
            return {"ok": False, "error": f"Results not found at {results_path}"}

        with open(results_path) as f:
            data = json.load(f)

        return {"ok": True, "data": data}


if __name__ == "__main__":
    SimulatorDaemon().run()
