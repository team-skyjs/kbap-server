import json
import socket
import unittest
import urllib.error
import urllib.request
from http.client import HTTPMessage, HTTPResponse, RemoteDisconnected

from tools.perf_dashboard.tests import test_server as server_tests


MANDATORY_HEADERS = ("Cache-Control", "X-Content-Type-Options", "Referrer-Policy")


class DashboardReviewSecurityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.case = server_tests.DashboardServerTest(methodName="test_targets_api_returns_metadata_without_secret_values")
        self.case.setUp()

    def tearDown(self) -> None:
        self.case.tearDown()

    def _method(self, method: str, host: str | None = None) -> tuple[int, HTTPMessage]:
        request = urllib.request.Request(self.case.base_url + "/api/targets", method=method)
        if host is not None:
            request.add_header("Host", host)
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error:
            with error:
                error.read()
                return error.code, error.headers
        with response:
            response.read()
            return response.status, response.headers

    def test_host_accepts_only_exact_loopback_name_and_actual_canonical_port(self) -> None:
        actual_port = self.case.server.server_port
        valid = ("localhost", f"localhost:{actual_port}", "127.0.0.1", f"127.0.0.1:{actual_port}")
        invalid = ("localhost:1", "localhost:abc", f"localhost:0{actual_port}", "localhost.", "localhost.evil", f"user@localhost:{actual_port}", f"[::1]:{actual_port}", f"127.0.0.1:{actual_port}:1")

        for host in valid:
            with self.subTest(host=host):
                self.assertEqual(200, self.case.request("/api/targets", host=host)[0])
        for host in invalid:
            with self.subTest(host=host):
                self.assertEqual(403, self.case.request("/api/targets", host=host)[0])

    def test_unsupported_methods_and_framework_501_share_security_headers(self) -> None:
        for method, expected in (("OPTIONS", 405), ("TRACE", 501), ("BREW", 501)):
            with self.subTest(method=method):
                status, headers = self._method(method)
                self.assertEqual(expected, status)
                for name in MANDATORY_HEADERS:
                    self.assertEqual(1, len(headers.get_all(name, [])))

    def test_unknown_method_with_non_loopback_host_is_forbidden(self) -> None:
        status, headers = self._method("BREW", host="evil.example")

        self.assertEqual(403, status)
        for name in MANDATORY_HEADERS:
            self.assertEqual(1, len(headers.get_all(name, [])))

    def test_missing_host_is_forbidden_with_security_headers(self) -> None:
        with socket.create_connection(("127.0.0.1", self.case.server.server_port), timeout=3) as connection:
            connection.sendall(b"GET /api/targets HTTP/1.0\r\n\r\n")
            response = HTTPResponse(connection)
            response.begin()
            response.read()

        self.assertEqual(403, response.status)
        for name in MANDATORY_HEADERS:
            self.assertEqual(1, len(response.headers.get_all(name, [])))

    def test_unknown_and_malformed_campaign_ids_are_not_found(self) -> None:
        for campaign_id in ("unknown", "..", "%2e%2e", "%2Fetc", "%252e%252e"):
            with self.subTest(campaign_id=campaign_id):
                self.assertEqual(404, self.case.request(f"/api/runs/{campaign_id}")[0])

    def test_unknown_campaign_cancel_is_not_found(self) -> None:
        status, _, _ = self.case.request("/api/runs/unknown/cancel", {})

        self.assertEqual(404, status)

    def test_huge_json_integer_returns_bounded_bad_request(self) -> None:
        huge_integer = "9" * 5000
        body = ('{"mode":"single","targetKey":"read-a","profile":"read","rateOrVus":' + huge_integer + ',"durationOrIterations":"1s"}').encode()
        request = urllib.request.Request(self.case.base_url + "/api/runs", data=body, headers={"Content-Type": "application/json"}, method="POST")

        try:
            urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error:
            with error:
                self.assertEqual(400, error.code)
                for name in MANDATORY_HEADERS:
                    self.assertEqual(1, len(error.headers.get_all(name, [])))
        except RemoteDisconnected as error:
            self.fail(f"server disconnected instead of returning 400: {error}")
        else:
            self.fail("huge integer request was accepted")

    def test_percent_encoded_campaign_id_is_not_an_alias_for_known_campaign(self) -> None:
        _, _, body = self.case.request("/api/runs", self.case.valid_payload())
        campaign_id = json.loads(body)["campaignId"]
        self.case.server.controller.wait_for_terminal(campaign_id, timeout=3)
        encoded_id = f"%{ord(campaign_id[0]):02X}{campaign_id[1:]}"

        status, _, _ = self.case.request(f"/api/runs/{encoded_id}")

        self.assertEqual(404, status)

    def test_registered_artifact_replaced_by_internal_symlink_is_not_served(self) -> None:
        _, _, body = self.case.request("/api/runs", self.case.valid_payload())
        campaign_id = json.loads(body)["campaignId"]
        campaign = self.case.server.controller.wait_for_terminal(campaign_id, timeout=3)
        report = next(artifact for artifact in campaign.targets[0].artifacts if artifact.name == "report.html")
        campaign_dir = self.case.artifact_root / campaign_id
        secret = campaign_dir / "secret.txt"
        secret.write_text("internal-secret", encoding="utf-8")
        report_path = campaign_dir / report.path
        report_path.unlink()
        report_path.symlink_to(secret)

        status, _, response_body = self.case.request(f"/api/runs/{campaign_id}/artifacts/{report.id}")

        self.assertEqual(404, status)
        self.assertNotIn(b"internal-secret", response_body)


if __name__ == "__main__":
    unittest.main()
