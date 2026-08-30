import json
import os
import selectors
import signal
import subprocess
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
from collections.abc import Mapping
from pathlib import Path

from tools.perf_dashboard.models import JsonValue
from tools.perf_dashboard.tests.fixtures import FAKE_RUNNER, TARGETS


class DashboardProcessLifecycleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.targets_path = self.root / "targets.json"
        self.targets_path.write_text(json.dumps(TARGETS), encoding="utf-8")
        self.runner = self.root / "fake-runner"
        self.runner.write_text(FAKE_RUNNER, encoding="utf-8")
        self.runner.chmod(0o700)
        self.record = self.root / "record.jsonl"
        self.signal_record = self.root / "signals"
        self.trap_entered = self.root / "trap-entered"
        self.trap_exited = self.root / "trap-exited"
        self.repo_root = Path(__file__).resolve().parents[3]
        self.processes: list[subprocess.Popen[str]] = []

    def tearDown(self) -> None:
        for process in self.processes:
            self._stop(process)
        if self.record.exists():
            for line in self.record.read_text(encoding="utf-8").splitlines():
                record = json.loads(line)
                if self._pid_exists(record["pid"]):
                    os.killpg(record["pgid"], signal.SIGKILL)
        self.temp_dir.cleanup()

    def _environment(self, artifact_root: Path, hold: bool = False) -> Mapping[str, str]:
        environment = os.environ.copy()
        environment.update({
            "ACCESS_TOKEN": "process-test-secret",
            "PERFORMANCE_ARTIFACT_ROOT": str(artifact_root),
            "FAKE_RECORD": str(self.record),
            "FAKE_SIGNAL_RECORD": str(self.signal_record),
            "FAKE_TRAP_ENTERED": str(self.trap_entered),
            "FAKE_TRAP_EXITED": str(self.trap_exited),
            "FAKE_HOLD": "1" if hold else "0",
        })
        return environment

    def _launch(self, artifact_root: Path, port: int = 0, hold: bool = False) -> subprocess.Popen[str]:
        process = subprocess.Popen(
            [sys.executable, "-m", "tools.perf_dashboard.tests.server_process", str(artifact_root), str(self.targets_path), str(self.runner), str(port)],
            cwd=self.repo_root,
            env=self._environment(artifact_root, hold),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.processes.append(process)
        return process

    def _ready_port(self, process: subprocess.Popen[str], timeout: float = 3.0) -> int | None:
        if process.stdout is None:
            return None
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            if not selector.select(timeout):
                return None
        line = process.stdout.readline().strip()
        return int(line.removeprefix("READY ")) if line.startswith("READY ") else None

    def _stop(self, process: subprocess.Popen[str]) -> None:
        if process.poll() is None:
            process.send_signal(signal.SIGINT)
            try:
                process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                stream.close()

    def _post(self, port: int, path: str, payload: Mapping[str, JsonValue]) -> tuple[int, bytes]:
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}",
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.read()
        with response:
            return response.status, response.read()

    def _wait_running(self, port: int, campaign_id: str) -> None:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/runs/{campaign_id}/events", timeout=3) as response:
            while line := response.readline():
                if b'"status":"RUNNING"' in line and b"phase=measurement" in line:
                    return
        self.fail("campaign never reached RUNNING")

    def _start_held_campaign(self, port: int) -> str:
        payload = {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": 1, "durationOrIterations": "1s"}
        status, body = self._post(port, "/api/runs", payload)
        self.assertEqual(202, status)
        campaign_id = json.loads(body)["campaignId"]
        self._wait_running(port, campaign_id)
        return campaign_id

    def _wait_terminal_events(self, port: int, campaign_id: str) -> None:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/runs/{campaign_id}/events", timeout=4) as response:
            response.read()

    def test_second_dashboard_on_different_port_cannot_recover_or_share_artifact_root(self) -> None:
        artifact_root = self.root / "shared-artifacts"
        first = self._launch(artifact_root, hold=True)
        first_port = self._ready_port(first)
        self.assertIsNotNone(first_port)
        campaign_id = self._start_held_campaign(first_port)

        second = self._launch(artifact_root)
        second_port = self._ready_port(second, timeout=1)
        persisted = json.loads((artifact_root / campaign_id / "campaign.json").read_text(encoding="utf-8"))
        if second_port is None:
            second.wait(timeout=2)
        else:
            self._stop(second)

        self.assertIsNone(second_port)
        self.assertEqual("RUNNING", persisted["status"])
        status, _ = self._post(first_port, "/api/runs", {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": 1, "durationOrIterations": "1s"})
        self.assertEqual(409, status)
        self._post(first_port, f"/api/runs/{campaign_id}/cancel", {})
        self._wait_terminal_events(first_port, campaign_id)

    def test_same_port_bind_failure_does_not_recover_campaign(self) -> None:
        occupied_root = self.root / "occupied"
        occupied = self._launch(occupied_root)
        occupied_port = self._ready_port(occupied)
        self.assertIsNotNone(occupied_port)
        candidate_root = self.root / "candidate"
        campaign_dir = candidate_root / "live-run"
        campaign_dir.mkdir(parents=True)
        campaign_file = campaign_dir / "campaign.json"
        campaign_file.write_text(json.dumps({"campaignId": "live-run", "status": "RUNNING", "targets": []}), encoding="utf-8")

        failed = self._launch(candidate_root, port=occupied_port)
        self.assertIsNone(self._ready_port(failed, timeout=1))
        failed.wait(timeout=2)

        self.assertEqual("RUNNING", json.loads(campaign_file.read_text(encoding="utf-8"))["status"])

    def test_sigint_shutdown_waits_for_runner_trap_and_leaves_no_process_group(self) -> None:
        artifact_root = self.root / "shutdown"
        server = self._launch(artifact_root, hold=True)
        port = self._ready_port(server)
        self.assertIsNotNone(port)
        self._start_held_campaign(port)
        runner_record = json.loads(self.record.read_text(encoding="utf-8").splitlines()[0])

        server.send_signal(signal.SIGINT)
        server.wait(timeout=4)
        runner_alive = self._pid_exists(runner_record["pid"])
        if runner_alive:
            os.killpg(runner_record["pgid"], signal.SIGKILL)
        replacement = self._launch(artifact_root)
        replacement_port = self._ready_port(replacement)

        self.assertTrue(self.trap_exited.exists())
        self.assertFalse(runner_alive)
        self.assertIsNotNone(replacement_port)

    def test_repeated_cancel_sends_one_sigint_and_one_bounded_sigterm(self) -> None:
        artifact_root = self.root / "idempotent"
        server = self._launch(artifact_root, hold=True)
        port = self._ready_port(server)
        self.assertIsNotNone(port)
        campaign_id = self._start_held_campaign(port)

        first_status, _ = self._post(port, f"/api/runs/{campaign_id}/cancel", {})
        second_status, _ = self._post(port, f"/api/runs/{campaign_id}/cancel", {})
        self._wait_terminal_events(port, campaign_id)

        self.assertEqual((202, 202), (first_status, second_status))
        self.assertEqual(["2", "15"], self.signal_record.read_text(encoding="utf-8").splitlines())

    def _pid_exists(self, pid: int) -> bool:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        return True


if __name__ == "__main__":
    unittest.main()
