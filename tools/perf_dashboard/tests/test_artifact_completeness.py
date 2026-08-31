import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from collections.abc import Mapping
from pathlib import Path

from tools.perf_dashboard.models import Artifact, JsonValue, RunStatus
from tools.perf_dashboard.server import create_server
from tools.perf_dashboard.tests.fixtures import FAKE_RUNNER, TARGETS


class ArtifactCompletenessTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.targets_path = self.root / "targets.json"
        self.targets_path.write_text(json.dumps(TARGETS), encoding="utf-8")
        self.artifact_root = self.root / "artifacts"
        self.runner = self.root / "fake-runner"
        self.runner.write_text(FAKE_RUNNER, encoding="utf-8")
        self.runner.chmod(0o700)
        self.old_env = os.environ.copy()
        os.environ.pop("FAKE_OMIT_ARTIFACT", None)
        os.environ.pop("FAKE_EXTRA_JFR", None)
        os.environ.update({
            "ACCESS_TOKEN": "api-super-secret",
            "PERFORMANCE_ARTIFACT_ROOT": str(self.artifact_root),
            "FAKE_RECORD": str(self.root / "record.jsonl"),
            "FAKE_TRAP_ENTERED": str(self.root / "trap-entered"),
            "FAKE_TRAP_EXITED": str(self.root / "trap-exited"),
            "FAKE_SIGNAL_RECORD": str(self.root / "signals"),
        })
        self.server = create_server(
            port=0,
            targets_path=self.targets_path,
            artifact_root=self.artifact_root,
            endpoint_runner=self.runner,
            cancel_grace_seconds=0.5,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        os.environ.clear()
        os.environ.update(self.old_env)
        self.temp_dir.cleanup()

    def request(self, path: str, payload: Mapping[str, JsonValue] | None = None) -> tuple[int, bytes]:
        data = None if payload is None else json.dumps(payload).encode()
        method = "POST" if data is not None else "GET"
        request = urllib.request.Request(self.base_url + path, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.read()
        with response:
            return response.status, response.read()

    def valid_payload(self) -> Mapping[str, JsonValue]:
        return {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": 1, "durationOrIterations": "1s"}

    def assert_download_contract(
        self,
        campaign_id: str,
        artifacts: tuple[Artifact, ...],
        expected: frozenset[str],
        omitted: tuple[str, ...] = (),
    ) -> None:
        self.assertEqual(expected, frozenset(artifact.name for artifact in artifacts))
        for artifact in artifacts:
            status, downloaded = self.request(f"/api/runs/{campaign_id}/artifacts/{artifact.id}")
            self.assertEqual(200, status)
            self.assertTrue(downloaded)
        for name in omitted:
            status, _ = self.request(f"/api/runs/{campaign_id}/artifacts/read-a:{name}")
            self.assertEqual(404, status)

    def test_exit_zero_with_each_missing_required_artifact_fails_and_keeps_partial_downloads(self) -> None:
        required = frozenset(("report.html", "summary.json", "manifest.json", "task-one.jfr", "task-two.jfr"))

        for missing in required:
            with self.subTest(missing=missing):
                os.environ["FAKE_OMIT_ARTIFACT"] = missing
                _, body = self.request("/api/runs", self.valid_payload())
                campaign_id = json.loads(body)["campaignId"]
                terminal = self.server.controller.wait_for_terminal(campaign_id, timeout=3)
                artifacts = terminal.targets[0].artifacts

                self.assertEqual(0, terminal.targets[0].exit_code)
                self.assertEqual(RunStatus.FAILED, terminal.status)
                self.assertEqual(RunStatus.FAILED, terminal.targets[0].status)
                self.assert_download_contract(campaign_id, artifacts, required - {missing}, (missing,))

    def test_jfr_disabled_exit_zero_still_requires_all_three_base_artifacts(self) -> None:
        required = frozenset(("report.html", "summary.json", "manifest.json"))
        payload = {"mode": "single", "targetKey": "read-a", "profile": "smoke", "rateOrVus": 1, "durationOrIterations": "1", "jfrEnabled": False}

        for missing in required:
            with self.subTest(missing=missing):
                os.environ["FAKE_OMIT_ARTIFACT"] = missing
                _, body = self.request("/api/runs", payload)
                campaign_id = json.loads(body)["campaignId"]
                terminal = self.server.controller.wait_for_terminal(campaign_id, timeout=3)
                artifacts = terminal.targets[0].artifacts

                self.assertEqual(0, terminal.targets[0].exit_code)
                self.assertEqual(RunStatus.FAILED, terminal.status)
                self.assertEqual(RunStatus.FAILED, terminal.targets[0].status)
                self.assert_download_contract(campaign_id, artifacts, required - {missing}, (missing,))

    def test_jfr_disabled_complete_base_set_passes_and_downloads_every_artifact(self) -> None:
        payload = {"mode": "single", "targetKey": "read-a", "profile": "smoke", "rateOrVus": 1, "durationOrIterations": "1", "jfrEnabled": False}

        _, body = self.request("/api/runs", payload)
        campaign_id = json.loads(body)["campaignId"]
        terminal = self.server.controller.wait_for_terminal(campaign_id, timeout=3)
        artifacts = terminal.targets[0].artifacts

        self.assertEqual(RunStatus.PASSED, terminal.status)
        self.assertEqual(RunStatus.PASSED, terminal.targets[0].status)
        self.assert_download_contract(campaign_id, artifacts, frozenset(("report.html", "summary.json", "manifest.json")))

    def test_jfr_enabled_third_allowlisted_recording_fails_but_registers_every_artifact(self) -> None:
        os.environ["FAKE_EXTRA_JFR"] = "1"
        expected = frozenset(("report.html", "summary.json", "manifest.json", "task-one.jfr", "task-two.jfr", "task-three.jfr"))

        _, body = self.request("/api/runs", self.valid_payload())
        campaign_id = json.loads(body)["campaignId"]
        terminal = self.server.controller.wait_for_terminal(campaign_id, timeout=3)
        artifacts = terminal.targets[0].artifacts

        self.assertEqual(0, terminal.targets[0].exit_code)
        self.assertEqual(RunStatus.FAILED, terminal.status)
        self.assertEqual(RunStatus.FAILED, terminal.targets[0].status)
        self.assert_download_contract(campaign_id, artifacts, expected)


if __name__ == "__main__":
    unittest.main()
