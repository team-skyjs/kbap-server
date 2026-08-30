import json
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.perf_dashboard.tests import test_server as server_tests


MANDATORY_HEADERS = ("Cache-Control", "X-Content-Type-Options", "Referrer-Policy")


class BundleServingSecurityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.case = server_tests.DashboardServerTest(methodName="test_targets_api_returns_metadata_without_secret_values")
        self.case.setUp()

    def tearDown(self) -> None:
        self.case.tearDown()

    def _completed_campaign(self) -> tuple[str, str]:
        _, _, body = self.case.request("/api/runs", self.case.valid_payload())
        campaign_id = json.loads(body)["campaignId"]
        campaign = self.case.server.controller.wait_for_terminal(campaign_id, timeout=3)
        report_id = next(str(artifact.id) for artifact in campaign.targets[0].artifacts if artifact.name == "report.html")
        return campaign_id, report_id

    def _assert_security_headers(self, headers: dict[str, str]) -> None:
        for name in MANDATORY_HEADERS:
            self.assertIn(name, headers)

    def test_open_bundle_stream_survives_path_replacement_with_symlink(self) -> None:
        campaign_id, _ = self._completed_campaign()
        campaign_dir = self.case.artifact_root / campaign_id
        secret = campaign_dir / "secret.txt"
        secret.write_bytes(b"bundle-secret")

        with self.case.server.controller.open_bundle(campaign_id) as opened:
            bundle_path = campaign_dir / "bundle.zip"
            bundle_path.unlink()
            bundle_path.symlink_to(secret)
            body = opened.source.read()

        self.assertTrue(body.startswith(b"PK"))
        self.assertNotIn(b"bundle-secret", body)

    def test_http_bundle_does_not_use_path_compatibility_helper(self) -> None:
        campaign_id, _ = self._completed_campaign()

        with patch.object(self.case.server.controller, "bundle", side_effect=AssertionError("path helper used")):
            status, _, body = self.case.request(f"/api/runs/{campaign_id}/bundle")

        self.assertEqual(200, status)
        self.assertTrue(body.startswith(b"PK"))

    def test_http_artifact_does_not_use_path_compatibility_helper(self) -> None:
        campaign_id, report_id = self._completed_campaign()

        with patch.object(self.case.server.controller, "resolve_artifact", side_effect=AssertionError("path helper used")):
            status, _, body = self.case.request(f"/api/runs/{campaign_id}/artifacts/{report_id}")

        self.assertEqual(200, status)
        self.assertEqual(b"<h1>safe</h1>", body)

    def test_bundle_temporary_filesystem_error_returns_secured_500(self) -> None:
        campaign_id, _ = self._completed_campaign()
        temporary = self.case.artifact_root / campaign_id / ".bundle.tmp"
        temporary.mkdir()

        status, headers, _ = self.case.request(f"/api/runs/{campaign_id}/bundle")

        self.assertEqual(500, status)
        self._assert_security_headers(headers)

    def test_missing_artifact_root_returns_secured_not_found_instead_of_disconnect(self) -> None:
        campaign_id, report_id = self._completed_campaign()
        moved_root = Path(self.case.temp_dir.name) / "moved-artifacts"
        self.case.artifact_root.rename(moved_root)

        status, headers, _ = self.case.request(f"/api/runs/{campaign_id}/artifacts/{report_id}")

        self.assertEqual(404, status)
        self._assert_security_headers(headers)


if __name__ == "__main__":
    unittest.main()
