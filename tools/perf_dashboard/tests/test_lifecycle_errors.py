import json
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.perf_dashboard.controller import CampaignShutdownTimeoutError
from tools.perf_dashboard.server import create_server
from tools.perf_dashboard.store import CampaignNotFoundError
from tools.perf_dashboard.tests.fixtures import TARGETS


class DashboardLifecycleErrorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.targets = self.root / "targets.json"
        self.targets.write_text(json.dumps(TARGETS), encoding="utf-8")
        self.runner = self.root / "unused-runner"
        self.runner.write_text("", encoding="utf-8")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def _free_port(self) -> int:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            return probe.getsockname()[1]

    def test_symlink_campaign_startup_error_releases_bound_socket_and_root_lock(self) -> None:
        artifact_root = self.root / "artifacts"
        external = self.root / "external"
        external.mkdir()
        (external / "campaign.json").write_text('{"campaignId":"live-run","status":"RUNNING","targets":[]}', encoding="utf-8")
        artifact_root.mkdir()
        symlink = artifact_root / "live-run"
        symlink.symlink_to(external, target_is_directory=True)
        port = self._free_port()

        with self.assertRaises(CampaignNotFoundError):
            create_server(port, self.targets, artifact_root, self.runner)
        symlink.unlink()
        replacement = create_server(port, self.targets, artifact_root, self.runner)

        replacement.server_close()

    def test_shutdown_timeout_error_still_releases_bound_socket_and_root_lock(self) -> None:
        artifact_root = self.root / "shutdown-artifacts"
        port = self._free_port()
        server = create_server(port, self.targets, artifact_root, self.runner)
        failure = CampaignShutdownTimeoutError(("live-run",))

        with patch.object(server.controller, "shutdown_active", side_effect=failure):
            with self.assertRaises(CampaignShutdownTimeoutError) as captured:
                server.server_close()
        replacement = create_server(port, self.targets, artifact_root, self.runner)

        self.assertIs(failure, captured.exception)
        replacement.server_close()


if __name__ == "__main__":
    unittest.main()
