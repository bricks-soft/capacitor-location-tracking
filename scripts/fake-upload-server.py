#!/usr/bin/env python3

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, Tuple

LOG_PATH = Path("/tmp/fake-upload-server.log")


def format_body(raw_body: bytes) -> Tuple[str, Any]:
    text = raw_body.decode("utf-8", errors="replace")
    try:
        return text, json.loads(text)
    except Exception:
        return text, None


def build_status(mode: str, count: int) -> int:
    if mode == "ok":
        return 200
    if mode == "401":
        return 401
    if mode == "500":
        return 500
    if mode == "flaky":
        return 500 if count % 2 == 1 else 200
    return 200


class Handler(BaseHTTPRequestHandler):
    request_counter = 0
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        self._handle_request()

    def do_PUT(self):
        self._handle_request()

    def do_PATCH(self):
        self._handle_request()

    def _handle_request(self) -> None:
        Handler.request_counter += 1
        raw_body = self._read_body()
        pretty_body, body = format_body(raw_body)
        status = build_status(self.server.mode, Handler.request_counter)

        headers_text = "\n".join(f"{key}: {value}" for key, value in self.headers.items())
        body_dump = pretty_body if body is None else json.dumps(body, indent=2, sort_keys=True)

        self._log_request(status, headers_text, body_dump)

        response = json.dumps({"ok": status < 400}).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def _read_body(self) -> bytes:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0:
            return b""
        return self.rfile.read(length)

    def _log_request(self, status: int, headers_text: str, body_dump: str) -> None:
        lines = [
            f"method={self.command}",
            f"path={self.path}",
            f"status={status}",
            "headers=\n" + headers_text,
            "body=\n" + body_dump,
            "",
        ]
        payload = "\n".join(lines)
        print(f"[{self.client_address[0]}] {payload}")
        sys.stdout.flush()

        try:
            with LOG_PATH.open("a", encoding="utf-8") as out:
                out.write(payload)
                out.write("\n")
        except Exception as error:
            print(f"log write failed: {error}")

    def log_message(self, *_args):
        # Keep default server logs quiet; we emit structured diagnostics above.
        return


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Dependency-free upload endpoint for emulator smoke tests")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument(
        "--mode",
        default="ok",
        choices=("ok", "401", "500", "flaky"),
        help="ok: always 200, 401: always 401, 500: always 500, flaky: 500/200 alternating",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.mode = args.mode
    print(f"fake-upload-server mode={args.mode} listening on {args.host}:{args.port}")
    print(f"log path: {LOG_PATH}")
    sys.stdout.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
