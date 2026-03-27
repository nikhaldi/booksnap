"""Shared helper for running `arl diagnose` and parsing its output."""

import json
import os
import subprocess
import sys
from pathlib import Path


def run_diagnose(data_dir: Path, env_extra: dict | None = None) -> dict:
    """Run `arl diagnose` and return the parsed JSON output."""
    env = {**os.environ, **(env_extra or {})}
    result = subprocess.run(
        ["arl", "diagnose", "--data", str(data_dir)],
        capture_output=True,
        text=True,
        env=env,
    )
    if result.returncode != 0:
        print(f"arl diagnose failed:\n{result.stderr}", file=sys.stderr)
        sys.exit(1)

    # Find where JSON starts (skip warning/info lines)
    lines = result.stdout.splitlines()
    for i, line in enumerate(lines):
        if line.strip().startswith("{"):
            return json.loads("\n".join(lines[i:]))

    return json.loads(result.stdout)
