#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlencode, urlsplit


RESPONSE_BODY = json.dumps(
    {
        "success": True,
        "payload": {
            "version": "1.0.0",
            "items": [],
            "hasNext": True,
            "nextCursor": "100",
        },
        "message": None,
        "code": None,
    },
).encode()
TICKET_RESPONSE_BODY = json.dumps(
    {
        "success": True,
        "payload": {"ticket": "mock-ticket", "expiresInSeconds": 300},
        "message": None,
        "code": None,
    },
).encode()
TICKET_FAILURE_BODY = json.dumps(
    {"success": False, "payload": None, "message": "mock ticket failure", "code": "SCAN-006"},
).encode()
MOCK_TICKET_FAILURE = os.environ.get("MOCK_TICKET_FAILURE") == "true"


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
        parsed = urlsplit(self.path)
        request_body = self.read_request_body()
        api_version = self.headers.get("X-API-Version")
        if api_version:
            query = urlencode(sorted(parse_qsl(parsed.query, keep_blank_values=True)))
            request_path = parsed.path + (f"?{query}" if query else "")
            authenticated = str(bool(self.headers.get("Authorization"))).lower()
            print(
                f"REQUEST\t{self.command}\t{request_path}\t{api_version}\t{authenticated}",
                flush=True,
            )
            if request_body:
                normalized_body = json.dumps(
                    json.loads(request_body),
                    ensure_ascii=False,
                    separators=(",", ":"),
                    sort_keys=True,
                )
                print(f"BODY\t{self.command}\t{parsed.path}\t{normalized_body}", flush=True)

        ticket_request = parsed.path == "/api/scans/tickets"
        status = 503 if ticket_request and MOCK_TICKET_FAILURE else 200
        if ticket_request and MOCK_TICKET_FAILURE:
            response_body = TICKET_FAILURE_BODY
        elif ticket_request:
            response_body = TICKET_RESPONSE_BODY
        else:
            response_body = RESPONSE_BODY

        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response_body)))
        self.end_headers()
        self.wfile.write(response_body)

    def read_request_body(self) -> bytes:
        content_length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(content_length) if content_length else b""

    def log_message(self, format: str, *args: str) -> None:
        return


def main() -> None:
    with ThreadingHTTPServer(("127.0.0.1", 18081), MockHandler) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
