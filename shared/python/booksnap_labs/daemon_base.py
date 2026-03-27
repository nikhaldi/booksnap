"""Base HTTP daemon for host-side services in booksnap labs.

Provides the HTTP server, action dispatch, arg parsing, and pipeline copy
logic shared between the Android emulator daemon and iOS simulator daemon.

Subclasses register handlers via `register_handlers()` and implement
platform-specific actions.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Callable


class DaemonHandler(BaseHTTPRequestHandler):
    """HTTP request handler that dispatches JSON actions to registered handlers."""

    # Set by DaemonBase before starting the server
    _handlers: dict[str, Callable[[dict], dict]] = {}

    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length else b"{}"

        try:
            request = json.loads(body) if body else {}
        except json.JSONDecodeError:
            self._respond(400, {"error": "Invalid JSON"})
            return

        action = request.get("action", "")

        if action not in self._handlers:
            self._respond(
                400,
                {
                    "error": f"Unknown action: {action!r}",
                    "allowed": list(self._handlers.keys()),
                },
            )
            return

        try:
            result = self._handlers[action](request)
            self._respond(200, result)
        except Exception as e:
            self._respond(500, {"error": str(e)})

    def _respond(self, status: int, data: dict) -> None:
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        print(f"  [daemon] {format % args}", file=sys.stderr)


class DaemonBase:
    """Base class for booksnap host-side daemons.

    Subclasses implement `register_handlers()` to define platform-specific
    actions. The base class handles HTTP server lifecycle, arg parsing,
    and pipeline copy logic.
    """

    def __init__(self, description: str, default_port: int = 9100):
        self.description = description
        self.default_port = default_port
        self.data_dir: Path | None = None
        self.pipeline_dir: Path | None = None
        self._handlers: dict[str, Callable[[dict], dict]] = {}

    def register(self, action: str, handler: Callable[[dict], dict]) -> None:
        """Register a handler for an action name."""
        self._handlers[action] = handler

    def register_handlers(self) -> None:
        """Override to register platform-specific handlers."""
        raise NotImplementedError

    def copy_pipeline(
        self,
        dest: Path,
        ignore_patterns: tuple[str, ...] = ("*.json", "*.md"),
    ) -> dict | None:
        """Copy pipeline source to the test harness. Returns error dict or None."""
        if self.pipeline_dir is None:
            return {"ok": False, "error": "No pipeline directory configured"}
        if not self.pipeline_dir.exists():
            return {"ok": False, "error": f"Pipeline dir not found: {self.pipeline_dir}"}
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(
            self.pipeline_dir,
            dest,
            ignore=shutil.ignore_patterns(*ignore_patterns),
        )
        return None

    def add_extra_args(self, parser: argparse.ArgumentParser) -> None:
        """Override to add platform-specific CLI arguments."""
        pass

    def run(self) -> None:
        """Parse args and start the HTTP server."""
        parser = argparse.ArgumentParser(description=self.description)
        parser.add_argument("--port", type=int, default=self.default_port,
                            help="Port to listen on")
        parser.add_argument("--data-dir", required=True,
                            help="Path to the data directory")
        parser.add_argument("--pipeline-dir", required=True,
                            help="Path to the pipeline source directory")
        self.add_extra_args(parser)
        args = parser.parse_args()

        self.data_dir = Path(args.data_dir).resolve()
        self.pipeline_dir = Path(args.pipeline_dir).resolve()

        if not self.data_dir.exists():
            print(f"Error: data dir not found: {self.data_dir}", file=sys.stderr)
            sys.exit(1)
        if not self.pipeline_dir.exists():
            print(f"Error: pipeline dir not found: {self.pipeline_dir}", file=sys.stderr)
            sys.exit(1)

        self.register_handlers()

        # Pass handlers to the request handler class
        DaemonHandler._handlers = self._handlers

        server = HTTPServer(("0.0.0.0", args.port), DaemonHandler)
        print(
            f"{self.description} listening on port {args.port}\n"
            f"Dataset dir:  {self.data_dir}\n"
            f"Pipeline dir: {self.pipeline_dir}",
            file=sys.stderr,
        )

        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down.", file=sys.stderr)
        finally:
            server.server_close()
