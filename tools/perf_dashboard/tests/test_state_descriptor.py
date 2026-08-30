import errno
import json
import os
import tempfile
import unittest
from pathlib import Path
from types import TracebackType
from unittest.mock import patch

from tools.perf_dashboard.artifacts import ArtifactStorageError, build_bundle
from tools.perf_dashboard.tests import test_server as server_tests


MANDATORY_HEADERS = ("Cache-Control", "X-Content-Type-Options", "Referrer-Policy")


class FailingReader:
    def __enter__(self) -> "FailingReader":
        return self

    def __exit__(self, exception_type: type[BaseException] | None, exception: BaseException | None, traceback: TracebackType | None) -> None:
        return None

    def read(self) -> bytes:
        raise OSError(errno.EIO, "injected read failure")


class CampaignStateDescriptorTest(unittest.TestCase):
    def _fd_count(self) -> int:
        directory = Path("/dev/fd") if Path("/dev/fd").is_dir() else Path("/proc/self/fd")
        return len(tuple(directory.iterdir()))

    def _campaign_dir(self, root: Path) -> Path:
        campaign_dir = root / "run-one"
        campaign_dir.mkdir()
        (campaign_dir / "campaign.json").write_text(json.dumps({"campaignId": "run-one", "status": "PASSED", "targets": []}), encoding="utf-8")
        return campaign_dir

    def test_fdopen_failure_maps_to_storage_error_without_leaking_raw_descriptors(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            campaign_dir = self._campaign_dir(Path(temporary))
            before = self._fd_count()

            with patch("tools.perf_dashboard.artifacts.os.fdopen", side_effect=OSError(errno.EIO, "injected fdopen failure")):
                for _ in range(75):
                    with self.assertRaises(ArtifactStorageError):
                        build_bundle(campaign_dir)

            self.assertLessEqual(self._fd_count(), before + 1)

    def test_read_failure_maps_to_storage_error_without_leaking_raw_descriptors(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            campaign_dir = self._campaign_dir(Path(temporary))
            before = self._fd_count()

            with patch("tools.perf_dashboard.artifacts.os.fdopen", return_value=FailingReader()):
                for _ in range(75):
                    with self.assertRaises(ArtifactStorageError):
                        build_bundle(campaign_dir)

            self.assertLessEqual(self._fd_count(), before + 1)


class CampaignStateHttpErrorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.case = server_tests.DashboardServerTest(methodName="test_targets_api_returns_metadata_without_secret_values")
        self.case.setUp()
        _, _, body = self.case.request("/api/runs", self.case.valid_payload())
        self.campaign_id = json.loads(body)["campaignId"]
        self.case.server.controller.wait_for_terminal(self.campaign_id, timeout=3)
        self.state = self.case.artifact_root / self.campaign_id / "campaign.json"

    def tearDown(self) -> None:
        self.case.tearDown()

    def _assert_repeated_error(self, expected: int) -> None:
        before = CampaignStateDescriptorTest()._fd_count()
        for _ in range(50):
            status, headers, _ = self.case.request(f"/api/runs/{self.campaign_id}/bundle")
            self.assertEqual(expected, status)
            for name in MANDATORY_HEADERS:
                self.assertIn(name, headers)
        self.assertLessEqual(CampaignStateDescriptorTest()._fd_count(), before + 1)

    def test_directory_campaign_state_returns_secured_404_without_fd_growth(self) -> None:
        self.state.unlink()
        self.state.mkdir()

        self._assert_repeated_error(404)

    def test_malformed_campaign_state_returns_secured_404_without_fd_growth(self) -> None:
        self.state.write_bytes(b"{not-json")

        self._assert_repeated_error(404)

    def test_invalid_campaign_schema_returns_secured_404_without_fd_growth(self) -> None:
        self.state.write_text(json.dumps({"campaignId": self.campaign_id, "status": "PASSED", "targets": "invalid"}), encoding="utf-8")

        self._assert_repeated_error(404)

    def test_unreadable_campaign_state_returns_secured_500(self) -> None:
        self.state.chmod(0)

        status, headers, _ = self.case.request(f"/api/runs/{self.campaign_id}/bundle")

        self.assertEqual(500, status)
        for name in MANDATORY_HEADERS:
            self.assertIn(name, headers)


if __name__ == "__main__":
    unittest.main()
