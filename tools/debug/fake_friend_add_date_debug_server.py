#!/usr/bin/env python3
"""
Local LAN debug server for FakeFriendAddDateNt.

Security notes:
- Intended for trusted LAN only.
- No shell execution, no eval, no arbitrary command support.
- Only white-listed commands are accepted from console input.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlparse


LOG_RELATIVE_PATH = Path("logs") / "fake_friend_add_date_debug.log"
ALLOWED_COMMANDS = {
    "ping",
    "dump_config",
    "dump_hook_state",
    "set_debug_log",
    "set_debug_server_enabled",
    "set_target",
    "set_fake_add_date",
    "clear_debug_counters",
}
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class CommandQueue:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._items: List[Dict[str, Any]] = []
        self._seq = 0

    def enqueue(self, command: Dict[str, Any]) -> Dict[str, Any]:
        with self._lock:
            self._seq += 1
            command = dict(command)
            command["id"] = command.get("id") or f"cmd-{int(time.time() * 1000)}-{self._seq}"
            self._items.append(command)
            return command

    def pop_all(self) -> List[Dict[str, Any]]:
        with self._lock:
            if not self._items:
                return []
            items = self._items
            self._items = []
            return items


class DebugState:
    def __init__(self, log_path: Path) -> None:
        self.command_queue = CommandQueue()
        self.log_path = log_path
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self._log_lock = threading.Lock()

    def append_log_line(self, line: str) -> None:
        with self._log_lock:
            with self.log_path.open("a", encoding="utf-8") as fp:
                fp.write(line.rstrip("\n"))
                fp.write("\n")


def now_iso() -> str:
    return datetime.now().isoformat(timespec="seconds")


def print_and_save(state: DebugState, category: str, payload: Any) -> None:
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    line = f"[{now_iso()}] [{category}] {text}"
    print(line)
    state.append_log_line(line)


def parse_bool(value: str) -> Optional[bool]:
    normalized = value.strip().lower()
    if normalized in {"1", "true", "on", "yes", "enabled"}:
        return True
    if normalized in {"0", "false", "off", "no", "disabled"}:
        return False
    return None


def parse_console_command(line: str) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    parts = [p for p in line.strip().split() if p]
    if not parts:
        return None, None
    cmd = parts[0]

    if cmd not in ALLOWED_COMMANDS:
        return None, f"unknown command: {cmd}"

    if cmd in {"ping", "dump_config", "dump_hook_state", "clear_debug_counters"}:
        return {"type": cmd}, None

    if cmd in {"set_debug_log", "set_debug_server_enabled"}:
        if len(parts) != 2:
            return None, f"usage: {cmd} true|false"
        enabled = parse_bool(parts[1])
        if enabled is None:
            return None, f"invalid boolean: {parts[1]}"
        return {"type": cmd, "params": {"enabled": enabled}}, None

    if cmd == "set_target":
        if len(parts) < 2:
            return None, "usage: set_target <uin> [uid]"
        payload: Dict[str, Any] = {"type": cmd, "params": {"target_uin": parts[1]}}
        if len(parts) >= 3:
            payload["params"]["target_uid"] = parts[2]
        return payload, None

    if cmd == "set_fake_add_date":
        if len(parts) != 2:
            return None, "usage: set_fake_add_date <yyyy-MM-dd>"
        if not DATE_RE.match(parts[1]):
            return None, "invalid date format, expected yyyy-MM-dd"
        return {"type": cmd, "params": {"fake_add_date": parts[1]}}, None

    return None, "invalid command"


def sanitize_query_keys(url: str) -> Dict[str, Any]:
    parsed = urlparse(url)
    query = parse_qs(parsed.query)
    return {
        "host": parsed.hostname or "",
        "path": parsed.path or "",
        "queryKeys": sorted(query.keys()),
    }


class RequestHandler(BaseHTTPRequestHandler):
    state: DebugState

    server_version = "FakeFriendAddDateDebugServer/1.0"
    protocol_version = "HTTP/1.1"

    def _write_json(self, status_code: int, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self) -> Optional[Dict[str, Any]]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0:
            return None
        raw = self.rfile.read(length)
        if not raw:
            return None
        try:
            obj = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            return None
        if isinstance(obj, dict):
            return obj
        return None

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/qauxv/commands":
            self._write_json(404, {"ok": False, "error": "not found"})
            return
        params = parse_qs(parsed.query)
        module = (params.get("module") or [""])[0]
        device = (params.get("device") or [""])[0]
        commands = self.state.command_queue.pop_all()
        print_and_save(
            self.state,
            "COMMAND_POLL",
            {
                "module": module,
                "device": device,
                "pendingCount": len(commands),
            },
        )
        self._write_json(200, {"commands": commands})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        body = self._read_json_body() or {}
        if parsed.path == "/qauxv/log":
            payload = {
                "path": parsed.path,
                "module": body.get("module"),
                "event": body.get("event"),
                "level": body.get("level"),
                "hookPoint": body.get("hookPoint"),
                "page": body.get("page"),
                "targetMatched": body.get("targetMatched"),
                "strategy": body.get("strategy"),
                "queryMeta": sanitize_query_keys(str(body.get("message", ""))) if isinstance(body.get("message"), str) else {},
                "raw": body,
            }
            print_and_save(self.state, "LOG", payload)
            self._write_json(200, {"ok": True})
            return
        if parsed.path == "/qauxv/command-result":
            payload = {
                "path": parsed.path,
                "module": body.get("module"),
                "commandId": body.get("commandId"),
                "ok": body.get("ok"),
                "error": body.get("error"),
                "result": body.get("result"),
            }
            print_and_save(self.state, "COMMAND_RESULT", payload)
            self._write_json(200, {"ok": True})
            return
        self._write_json(404, {"ok": False, "error": "not found"})

    def log_message(self, fmt: str, *args: Any) -> None:
        # Silence default access logs, we already print structured logs above.
        return


def console_input_loop(state: DebugState) -> None:
    print("Input commands. Type 'help' to list commands, 'exit' to stop.")
    while True:
        try:
            line = input("debug-cmd> ").strip()
        except EOFError:
            return
        except KeyboardInterrupt:
            return
        if not line:
            continue
        if line in {"exit", "quit"}:
            os._exit(0)
        if line == "help":
            print(
                "Allowed commands:\n"
                "  ping\n"
                "  dump_config\n"
                "  dump_hook_state\n"
                "  set_debug_log true|false\n"
                "  set_debug_server_enabled true|false\n"
                "  set_target <uin> [uid]\n"
                "  set_fake_add_date <yyyy-MM-dd>\n"
                "  clear_debug_counters\n"
            )
            continue
        parsed, error = parse_console_command(line)
        if error:
            print(f"[error] {error}")
            continue
        if not parsed:
            continue
        command = state.command_queue.enqueue(parsed)
        print_and_save(state, "COMMAND_ENQUEUE", command)


def main() -> int:
    parser = argparse.ArgumentParser(description="FakeFriendAddDateNt LAN debug server")
    parser.add_argument("--host", default="0.0.0.0", help="bind host, default: 0.0.0.0")
    parser.add_argument("--port", type=int, default=8848, help="bind port, default: 8848")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    log_path = repo_root / "tools" / "debug" / LOG_RELATIVE_PATH
    state = DebugState(log_path=log_path)
    RequestHandler.state = state

    server = ThreadingHTTPServer((args.host, args.port), RequestHandler)
    print(f"[{now_iso()}] Server listening on http://{args.host}:{args.port}")
    print(f"[{now_iso()}] Log file: {log_path}")
    print(f"[{now_iso()}] WARNING: Use in trusted LAN only, do not expose to public internet.")

    input_thread = threading.Thread(target=console_input_loop, args=(state,), daemon=True)
    input_thread.start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
