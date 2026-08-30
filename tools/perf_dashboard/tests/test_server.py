import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from collections.abc import Mapping
from pathlib import Path

from tools.perf_dashboard.models import JsonValue, RunStatus
from tools.perf_dashboard.server import create_server
from tools.perf_dashboard.tests.fixtures import FAKE_RUNNER, TARGETS


class DashboardServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.targets_path = self.root / "targets.json"
        self.targets_path.write_text(json.dumps(TARGETS), encoding="utf-8")
        self.artifact_root = self.root / "artifacts"
        self.runner = self.root / "fake-runner"
        self.runner.write_text(FAKE_RUNNER, encoding="utf-8")
        self.runner.chmod(0o700)
        self.record = self.root / "record.jsonl"
        self.trap_entered = self.root / "trap-entered"
        self.trap_exited = self.root / "trap-exited"
        self.signal_record = self.root / "signals"
        self.old_env = os.environ.copy()
        os.environ.update({
            "ACCESS_TOKEN": "api-super-secret",
            "PERFORMANCE_ARTIFACT_ROOT": str(self.artifact_root),
            "FAKE_RECORD": str(self.record),
            "FAKE_TRAP_ENTERED": str(self.trap_entered),
            "FAKE_TRAP_EXITED": str(self.trap_exited),
            "FAKE_SIGNAL_RECORD": str(self.signal_record),
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

    def request(self, path: str, payload: Mapping[str, JsonValue] | None = None, host: str | None = None) -> tuple[int, Mapping[str, str], bytes]:
        data = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(self.base_url + path, data=data, method="POST" if data is not None else "GET")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if host is not None:
            request.add_header("Host", host)
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, dict(error.headers.items()), error.read()
        with response:
            return response.status, dict(response.headers.items()), response.read()

    def valid_payload(self) -> Mapping[str, JsonValue]:
        return {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": 1, "durationOrIterations": "1s"}

    def test_targets_api_returns_metadata_without_secret_values(self) -> None:
        status, headers, body = self.request("/api/targets")

        document = json.loads(body)
        self.assertEqual(200, status)
        self.assertEqual("Read A", document["targets"][0]["label"])
        self.assertNotIn("api-super-secret", body.decode())
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual("nosniff", headers["X-Content-Type-Options"])
        self.assertEqual("no-referrer", headers["Referrer-Policy"])

    def test_valid_run_returns_queued_and_executes_fake_runner(self) -> None:
        status, _, body = self.request("/api/runs", self.valid_payload())

        campaign = json.loads(body)
        terminal = self.server.controller.wait_for_terminal(campaign["campaignId"], timeout=3)
        record = json.loads(self.record.read_text(encoding="utf-8").splitlines()[0])
        self.assertEqual(202, status)
        self.assertEqual("QUEUED", campaign["status"])
        self.assertEqual(RunStatus.PASSED, terminal.status)
        self.assertEqual(["read-a", "read", "1", "1s"], record["argv"])
        self.assertIn("ACCESS_TOKEN", record["envNames"])
        self.assertNotIn("api-super-secret", json.dumps(record))
        self.assertTrue(campaign["jfrEnabled"])
        self.assertEqual(12.5, terminal.targets[0].summary.p95)
        self.assertEqual(17.5, terminal.targets[0].summary.p99)
        self.assertEqual(0.25, terminal.targets[0].summary.failure_rate)
        self.assertEqual(3.0, terminal.targets[0].summary.dropped_iterations)
        self.assertTrue(terminal.targets[0].summary.thresholds_passed)
        self.assertEqual(5, len(terminal.targets[0].artifacts))

    def test_nonzero_runner_exit_persists_failed_target_and_campaign(self) -> None:
        os.environ["FAKE_EXIT_CODE"] = "23"

        _, _, body = self.request("/api/runs", self.valid_payload())
        campaign_id = json.loads(body)["campaignId"]
        terminal = self.server.controller.wait_for_terminal(campaign_id, timeout=3)

        self.assertEqual(RunStatus.FAILED, terminal.status)
        self.assertEqual(RunStatus.FAILED, terminal.targets[0].status)
        self.assertEqual(23, terminal.targets[0].exit_code)

    def test_selected_targets_execute_serially_in_manifest_order(self) -> None:
        payload = {"mode": "selected", "targetKeys": ["read-b", "read-a"], "profile": "read", "rateOrVus": 1, "durationOrIterations": "1s"}

        _, _, body = self.request("/api/runs", payload)
        campaign_id = json.loads(body)["campaignId"]
        terminal = self.server.controller.wait_for_terminal(campaign_id, timeout=3)

        records = [json.loads(line) for line in self.record.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["read-a", "read-b"], [record["argv"][0] for record in records])
        self.assertEqual([RunStatus.PASSED, RunStatus.PASSED], [target.status for target in terminal.targets])

    def test_invalid_run_requests_return_bad_request(self) -> None:
        payloads = (
            {"mode": "single", "targetKey": "missing", "profile": "read", "rateOrVus": 1, "durationOrIterations": "1s"},
            {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": -1, "durationOrIterations": "1s"},
            {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": 41, "durationOrIterations": "1s"},
            {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": 1, "durationOrIterations": "bad"},
            {"mode": "single", "targetKey": "cost-a", "profile": "external", "rateOrVus": 1, "durationOrIterations": "1"},
        )

        for payload in payloads:
            with self.subTest(payload=payload):
                status, _, _ = self.request("/api/runs", payload)
                self.assertEqual(400, status)

    def test_non_json_run_request_is_rejected_before_validation(self) -> None:
        body = json.dumps(self.valid_payload()).encode()
        request = urllib.request.Request(case_url := self.base_url + "/api/runs", data=body, headers={"Content-Type": "text/plain"}, method="POST")

        with self.assertRaises(urllib.error.HTTPError) as captured:
            urllib.request.urlopen(request, timeout=3)

        self.assertEqual(case_url, captured.exception.url)
        self.assertEqual(400, captured.exception.code)
        captured.exception.close()

    def test_run_snapshot_and_history_are_restored_from_controller_state(self) -> None:
        _, _, body = self.request("/api/runs", self.valid_payload())
        campaign_id = json.loads(body)["campaignId"]
        self.server.controller.wait_for_terminal(campaign_id, timeout=3)

        snapshot_status, _, snapshot_body = self.request(f"/api/runs/{campaign_id}")
        history_status, _, history_body = self.request("/api/runs")

        self.assertEqual(200, snapshot_status)
        self.assertEqual("PASSED", json.loads(snapshot_body)["status"])
        self.assertEqual(200, history_status)
        self.assertEqual(campaign_id, json.loads(history_body)["runs"][0]["campaignId"])

    def test_second_active_campaign_returns_conflict(self) -> None:
        os.environ["FAKE_HOLD"] = "1"
        _, _, first_body = self.request("/api/runs", self.valid_payload())
        campaign_id = json.loads(first_body)["campaignId"]
        self.server.controller.wait_for_status(campaign_id, RunStatus.RUNNING, timeout=3)

        status, _, body = self.request("/api/runs", self.valid_payload())

        self.assertEqual(409, status)
        self.assertEqual("active-campaign-exists", json.loads(body)["error"])
        self.request(f"/api/runs/{campaign_id}/cancel", {})
        self.server.controller.wait_for_terminal(campaign_id, timeout=3)

    def test_cancel_stays_cancelling_until_runner_trap_exits(self) -> None:
        os.environ["FAKE_HOLD"] = "1"
        payload = {"mode": "selected", "targetKeys": ["read-a", "read-b"], "profile": "read", "rateOrVus": 1, "durationOrIterations": "1s"}
        _, _, first_body = self.request("/api/runs", payload)
        campaign_id = json.loads(first_body)["campaignId"]
        self.server.controller.wait_for_event(campaign_id, "phase=measurement", timeout=3)

        status, _, _ = self.request(f"/api/runs/{campaign_id}/cancel", {})
        cleanup_event = self.server.controller.wait_for_event(campaign_id, "phase=cleanup", timeout=3)
        cancelling = self.server.controller.get(campaign_id)
        terminal = self.server.controller.wait_for_terminal(campaign_id, timeout=3)

        self.assertEqual(202, status)
        self.assertEqual(RunStatus.CANCELLING, cleanup_event.status)
        self.assertEqual(RunStatus.CANCELLING, cancelling.status)
        self.assertEqual("2", self.trap_entered.read_text(encoding="utf-8"))
        self.assertEqual("15", self.trap_exited.read_text(encoding="utf-8"))
        self.assertTrue(self.trap_exited.exists())
        self.assertEqual(RunStatus.CANCELLED, terminal.status)
        self.assertEqual([RunStatus.CANCELLED, RunStatus.CANCELLED], [target.status for target in terminal.targets])
        self.assertEqual(1, len(self.record.read_text(encoding="utf-8").splitlines()))

    def test_sse_is_sequenced_and_sanitized(self) -> None:
        _, _, first_body = self.request("/api/runs", self.valid_payload())
        campaign_id = json.loads(first_body)["campaignId"]
        self.server.controller.wait_for_terminal(campaign_id, timeout=3)

        status, headers, body = self.request(f"/api/runs/{campaign_id}/events")

        text = body.decode()
        self.assertEqual(200, status)
        self.assertEqual("text/event-stream", headers["Content-Type"])
        self.assertIn("id: 1", text)
        self.assertNotIn("runner-secret", text)
        self.assertIn("[REDACTED]", text)

    def test_artifacts_are_manifest_allowlisted_and_report_is_sandboxed(self) -> None:
        _, _, first_body = self.request("/api/runs", self.valid_payload())
        campaign_id = json.loads(first_body)["campaignId"]
        campaign = self.server.controller.wait_for_terminal(campaign_id, timeout=3)
        report_id = next(artifact.id for artifact in campaign.targets[0].artifacts if artifact.name == "report.html")
        jfr_id = next(artifact.id for artifact in campaign.targets[0].artifacts if artifact.name == "task-one.jfr")

        status, headers, body = self.request(f"/api/runs/{campaign_id}/artifacts/{report_id}")
        missing_status, _, _ = self.request(f"/api/runs/{campaign_id}/artifacts/not-registered")
        jfr_status, jfr_headers, jfr_body = self.request(f"/api/runs/{campaign_id}/artifacts/{jfr_id}")
        bundle_status, bundle_headers, bundle = self.request(f"/api/runs/{campaign_id}/bundle")

        self.assertEqual(200, status)
        self.assertEqual("inline", headers["Content-Disposition"])
        self.assertEqual("sandbox; default-src 'none'; style-src 'unsafe-inline'", headers["Content-Security-Policy"])
        self.assertEqual(b"<h1>safe</h1>", body)
        self.assertEqual(404, missing_status)
        self.assertEqual(200, jfr_status)
        self.assertEqual("application/octet-stream", jfr_headers["Content-Type"])
        self.assertTrue(jfr_headers["Content-Disposition"].startswith("attachment;"))
        self.assertEqual(b"jfr-one", jfr_body)
        self.assertEqual(200, bundle_status)
        self.assertEqual("application/zip", bundle_headers["Content-Type"])
        self.assertTrue(bundle.startswith(b"PK"))

    def test_non_loopback_host_is_forbidden(self) -> None:
        status, headers, _ = self.request("/api/targets", host="evil.example")

        self.assertEqual(403, status)
        self.assertEqual("no-store", headers["Cache-Control"])


if __name__ == "__main__":
    unittest.main()
