#!/usr/bin/env python3
from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


RESPONSE_BODY = json.dumps(
    {"success": True, "payload": {"version": "1.0.0"}, "message": None, "code": None},
).encode()


class MockHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        self.respond()

    def do_POST(self) -> None:
        self.respond()

    def do_PUT(self) -> None:
        self.respond()

    def do_PATCH(self) -> None:
        self.respond()

    def do_DELETE(self) -> None:
        self.respond()

    def respond(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(RESPONSE_BODY)))
        self.end_headers()
        self.wfile.write(RESPONSE_BODY)

    def log_message(self, format: str, *args: str) -> None:
        return


def main() -> None:
    with ThreadingHTTPServer(("127.0.0.1", 18081), MockHandler) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
