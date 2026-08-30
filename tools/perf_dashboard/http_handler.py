import json
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import unquote, urlsplit

from .artifacts import ArtifactNotFoundError, CHUNK_SIZE
from .controller import ActiveCampaignError
from .models import JsonValue, RunStatus, campaign_document, target_api_document
from .store import CampaignNotFoundError
from .validation import RequestValidationError, validate_run_request


SECURITY_HEADERS = (
    ("Cache-Control", "no-store"),
    ("X-Content-Type-Options", "nosniff"),
    ("Referrer-Policy", "no-referrer"),
)
DOCUMENT_CSP = "default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'self'; base-uri 'none'; form-action 'self'"
REPORT_CSP = "sandbox; default-src 'none'; style-src 'unsafe-inline'"


@dataclass(frozen=True, slots=True)
class InvalidPayloadError(Exception):
    code: str

    def __str__(self) -> str:
        return self.code


class DashboardHandler(BaseHTTPRequestHandler):
    server_version = "KbapPerfDashboard/1"

    def do_GET(self) -> None:
        if not self._loopback_host():
            self._json(HTTPStatus.FORBIDDEN, {"error": "loopback-host-required"})
            return
        path = urlsplit(self.path).path
        if path == "/api/targets":
            self._json(HTTPStatus.OK, {"targets": [target_api_document(target) for target in self.server.targets]})
            return
        if path == "/api/runs":
            self._json(HTTPStatus.OK, {"runs": [campaign_document(item) for item in self.server.controller.list()]})
            return
        segments = tuple(unquote(segment) for segment in path.strip("/").split("/"))
        try:
            if len(segments) == 3 and segments[:2] == ("api", "runs"):
                self._json(HTTPStatus.OK, campaign_document(self.server.controller.get(segments[2])))
                return
            if len(segments) == 4 and segments[:2] == ("api", "runs") and segments[3] == "events":
                self._sse(segments[2])
                return
            if len(segments) == 4 and segments[:2] == ("api", "runs") and segments[3] == "bundle":
                self._stream_file(self.server.controller.bundle(segments[2]), "application/zip", "attachment")
                return
            if len(segments) == 5 and segments[:2] == ("api", "runs") and segments[3] == "artifacts":
                self._artifact(segments[2], segments[4])
                return
        except CampaignNotFoundError:
            self._json(HTTPStatus.NOT_FOUND, {"error": "campaign-not-found"})
            return
        except ArtifactNotFoundError:
            self._json(HTTPStatus.NOT_FOUND, {"error": "artifact-not-found"})
            return
        if self._static(path):
            return
        self._json(HTTPStatus.NOT_FOUND, {"error": "not-found"})

    def do_POST(self) -> None:
        if not self._loopback_host():
            self._json(HTTPStatus.FORBIDDEN, {"error": "loopback-host-required"})
            return
        path = urlsplit(self.path).path
        try:
            if path == "/api/runs":
                payload = self._payload()
                request = validate_run_request(payload, self.server.targets)
                campaign = self.server.controller.start(request)
                self._json(HTTPStatus.ACCEPTED, campaign_document(campaign))
                return
            segments = tuple(unquote(segment) for segment in path.strip("/").split("/"))
            if len(segments) == 4 and segments[:2] == ("api", "runs") and segments[3] == "cancel":
                if self.server.controller.cancel(segments[2]):
                    self._json(HTTPStatus.ACCEPTED, {"status": RunStatus.CANCELLING.value})
                else:
                    self._json(HTTPStatus.CONFLICT, {"error": "campaign-not-active"})
                return
        except InvalidPayloadError as error:
            self._json(HTTPStatus.BAD_REQUEST, {"error": error.code})
            return
        except RequestValidationError as error:
            self._json(HTTPStatus.BAD_REQUEST, {"error": error.code})
            return
        except ActiveCampaignError:
            self._json(HTTPStatus.CONFLICT, {"error": "active-campaign-exists"})
            return
        self._json(HTTPStatus.NOT_FOUND, {"error": "not-found"})

    def do_DELETE(self) -> None:
        self._method_not_allowed()

    def do_HEAD(self) -> None:
        self._method_not_allowed()

    def do_OPTIONS(self) -> None:
        self._method_not_allowed()

    def do_PATCH(self) -> None:
        self._method_not_allowed()

    def do_PUT(self) -> None:
        self._method_not_allowed()

    def _method_not_allowed(self) -> None:
        if self._loopback_host():
            self._json(HTTPStatus.METHOD_NOT_ALLOWED, {"error": "method-not-allowed"})
        else:
            self._json(HTTPStatus.FORBIDDEN, {"error": "loopback-host-required"})

    def _payload(self) -> dict[str, JsonValue]:
        content_type = self.headers.get("Content-Type", "").partition(";")[0].strip().lower()
        if content_type != "application/json":
            raise InvalidPayloadError("invalid-content-type")
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            raise InvalidPayloadError("missing-content-length")
        try:
            length = int(raw_length)
        except ValueError as error:
            raise InvalidPayloadError("invalid-content-length") from error
        if length < 1 or length > 65_536:
            raise InvalidPayloadError("invalid-content-length")
        try:
            document: JsonValue = json.loads(self.rfile.read(length))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise InvalidPayloadError("invalid-json") from error
        if not isinstance(document, dict):
            raise InvalidPayloadError("invalid-json")
        return document

    def _loopback_host(self) -> bool:
        host = self.headers.get("Host", "")
        hostname = host.rsplit(":", 1)[0] if ":" in host else host
        return hostname in ("127.0.0.1", "localhost")

    def _headers(self, status: int, content_type: str, length: int | None = None, csp: str | None = None, disposition: str | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        for name, value in SECURITY_HEADERS:
            self.send_header(name, value)
        if length is not None:
            self.send_header("Content-Length", str(length))
        if csp is not None:
            self.send_header("Content-Security-Policy", csp)
        if disposition is not None:
            self.send_header("Content-Disposition", disposition)
        self.end_headers()

    def _json(self, status: int, document: JsonValue) -> None:
        body = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode()
        self._headers(status, "application/json; charset=utf-8", len(body))
        self.wfile.write(body)

    def _artifact(self, campaign_id: str, artifact_id: str) -> None:
        path = self.server.controller.resolve_artifact(campaign_id, artifact_id)
        if path.name == "report.html":
            self._stream_file(path, "text/html; charset=utf-8", "inline", REPORT_CSP)
            return
        media_type = "application/json" if path.suffix == ".json" else "application/octet-stream"
        self._stream_file(path, media_type, f'attachment; filename="{path.name}"')

    def _stream_file(self, path: Path, media_type: str, disposition: str, csp: str | None = None) -> None:
        self._headers(HTTPStatus.OK, media_type, path.stat().st_size, csp, disposition)
        with path.open("rb") as source:
            while chunk := source.read(CHUNK_SIZE):
                self.wfile.write(chunk)

    def _sse(self, campaign_id: str) -> None:
        campaign = self.server.controller.get(campaign_id)
        events = self.server.controller.events(campaign_id)
        raw_sequence = self.headers.get("Last-Event-ID", "0")
        sequence = int(raw_sequence) if raw_sequence.isdecimal() else 0
        self._headers(HTTPStatus.OK, "text/event-stream")
        try:
            while True:
                available = events.after(sequence)
                for event in available:
                    data = json.dumps(event.document(), ensure_ascii=False, separators=(",", ":"))
                    self.wfile.write(f"id: {event.sequence}\nevent: campaign\ndata: {data}\n\n".encode())
                    self.wfile.flush()
                    sequence = event.sequence
                campaign = self.server.controller.get(campaign_id)
                if campaign.status in (RunStatus.PASSED, RunStatus.FAILED, RunStatus.CANCELLED):
                    return
                events.wait_after(sequence, timeout=15.0)
        except (BrokenPipeError, ConnectionResetError):
            return

    def _static(self, path: str) -> bool:
        names = {"/": "index.html", "/index.html": "index.html", "/showcase.html": "showcase.html", "/styles.css": "styles.css", "/app.js": "app.js"}
        name = names.get(path)
        if name is None:
            return False
        static_path = self.server.static_root / name
        if not static_path.is_file():
            return False
        media_type = "text/html; charset=utf-8" if name.endswith(".html") else ("text/css; charset=utf-8" if name.endswith(".css") else "text/javascript; charset=utf-8")
        csp = DOCUMENT_CSP if name.endswith(".html") else None
        self._stream_file(static_path, media_type, "inline", csp)
        return True

    def log_message(self, format: str, *args: JsonValue) -> None:
        return
