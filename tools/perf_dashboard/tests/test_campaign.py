import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.perf_dashboard.artifacts import ArtifactNotFoundError, build_bundle, discover_artifacts
from tools.perf_dashboard.events import EventBuffer, sanitize_line
from tools.perf_dashboard.models import RunStatus
from tools.perf_dashboard.store import CampaignStore
from tools.perf_dashboard.validation import RequestValidationError, load_targets, validate_run_request


TARGETS = {
    "targets": [
        {"key": "read-a", "label": "Read A", "method": "GET", "route": "/a", "suite": "read", "risk": "safe", "defaultProfile": "read", "defaultEnabled": True},
        {"key": "fixture-a", "label": "Fixture A", "method": "POST", "route": "/b", "suite": "fixture-write", "risk": "fixture", "defaultProfile": "write", "defaultEnabled": False},
        {"key": "read-b", "label": "Read B", "method": "GET", "route": "/c", "suite": "read", "risk": "safe", "defaultProfile": "read", "defaultEnabled": True},
        {"key": "cost-a", "label": "Cost A", "method": "POST", "route": "/d", "suite": "external", "risk": "cost", "defaultProfile": "external", "defaultEnabled": False},
    ]
}


class CampaignValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.target_path = self.root / "targets.json"
        self.target_path.write_text(json.dumps(TARGETS), encoding="utf-8")
        self.targets = load_targets(self.target_path)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_safe_all_preserves_manifest_order_when_defaults_are_safe(self) -> None:
        request = validate_run_request(
            {"mode": "safe-all", "profile": "read", "rateOrVus": 10, "durationOrIterations": "30s"},
            self.targets,
        )

        self.assertEqual(["read-a", "read-b"], [target.key for target in request.targets])
        self.assertTrue(request.jfr_enabled)

    def test_risky_target_is_rejected_when_allow_risk_is_not_true(self) -> None:
        with self.assertRaises(RequestValidationError):
            validate_run_request(
                {"mode": "selected", "targetKeys": ["fixture-a"], "profile": "write", "rateOrVus": 1, "durationOrIterations": "1s"},
                self.targets,
            )

    def test_jfr_off_is_only_allowed_for_one_smoke_target(self) -> None:
        valid = validate_run_request(
            {"mode": "single", "targetKey": "read-a", "profile": "smoke", "rateOrVus": 1, "durationOrIterations": "1", "jfrEnabled": False},
            self.targets,
        )

        self.assertFalse(valid.jfr_enabled)
        with self.assertRaises(RequestValidationError):
            validate_run_request(
                {"mode": "selected", "targetKeys": ["read-a", "read-b"], "profile": "smoke", "rateOrVus": 1, "durationOrIterations": "1", "jfrEnabled": False},
                self.targets,
            )

    def test_invalid_boundary_values_are_rejected(self) -> None:
        invalid_payloads = (
            {"mode": "single", "targetKey": "missing", "profile": "read", "rateOrVus": 1, "durationOrIterations": "1s"},
            {"mode": "single", "targetKey": "read-a", "profile": "unknown", "rateOrVus": 1, "durationOrIterations": "1s"},
            {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": -1, "durationOrIterations": "1s"},
            {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": 41, "durationOrIterations": "1s"},
            {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": 1, "durationOrIterations": "301s"},
            {"mode": "single", "targetKey": "read-a", "profile": "read", "rateOrVus": 1, "durationOrIterations": "forever"},
            {"mode": "single", "targetKey": "read-a", "profile": "smoke", "rateOrVus": 2, "durationOrIterations": "1"},
            {"mode": "single", "targetKey": "read-a", "profile": "smoke", "rateOrVus": 1, "durationOrIterations": "2"},
            {"mode": "single", "targetKey": "read-a", "profile": "write", "rateOrVus": 11, "durationOrIterations": "1s"},
            {"mode": "single", "targetKey": "read-a", "profile": "write", "rateOrVus": 1, "durationOrIterations": "121s"},
            {"mode": "single", "targetKey": "cost-a", "profile": "external", "rateOrVus": 11, "durationOrIterations": "1", "allowRisk": True},
            {"mode": "single", "targetKey": "cost-a", "profile": "external", "rateOrVus": 1, "durationOrIterations": "11", "allowRisk": True},
        )

        for payload in invalid_payloads:
            with self.subTest(payload=payload), self.assertRaises(RequestValidationError):
                validate_run_request(payload, self.targets)


class StoreAndArtifactTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_restart_recovers_active_campaign_as_failed_atomically(self) -> None:
        campaign_dir = self.root / "run-1"
        campaign_dir.mkdir()
        state = {"campaignId": "run-1", "status": "RUNNING", "targets": []}
        (campaign_dir / "campaign.json").write_text(json.dumps(state), encoding="utf-8")
        cancelling_dir = self.root / "run-cancelling"
        cancelling_dir.mkdir()
        cancelling_state = {"campaignId": "run-cancelling", "status": "CANCELLING", "targets": []}
        (cancelling_dir / "campaign.json").write_text(json.dumps(cancelling_state), encoding="utf-8")

        recovered = CampaignStore(self.root).recover_interrupted()

        self.assertEqual([RunStatus.FAILED, RunStatus.FAILED], [campaign.status for campaign in recovered])
        persisted = json.loads((campaign_dir / "campaign.json").read_text(encoding="utf-8"))
        self.assertEqual("control-server-restarted", persisted["failureReason"])
        self.assertFalse((campaign_dir / ".campaign.json.tmp").exists())

    def test_bundle_is_deterministic_and_contains_only_allowlisted_artifacts(self) -> None:
        campaign_dir = self.root / "run-2"
        target_dir = campaign_dir / "read-a"
        target_dir.mkdir(parents=True)
        (campaign_dir / "campaign.json").write_text('{"campaignId":"run-2"}', encoding="utf-8")
        for name in ("report.html", "summary.json", "manifest.json", "task-a.jfr", "task-b.jfr"):
            (target_dir / name).write_text(name, encoding="utf-8")
        (target_dir / "console.log").write_text("Bearer secret", encoding="utf-8")

        first = build_bundle(campaign_dir).read_bytes()
        (campaign_dir / "bundle.zip").unlink()
        second_path = build_bundle(campaign_dir)

        self.assertEqual(first, second_path.read_bytes())
        with zipfile.ZipFile(second_path) as archive:
            self.assertEqual(
                ["campaign.json", "read-a/manifest.json", "read-a/report.html", "read-a/summary.json", "read-a/task-a.jfr", "read-a/task-b.jfr"],
                archive.namelist(),
            )

    def test_artifact_discovery_uses_one_canonical_root_for_containment_and_relative_path(self) -> None:
        campaign_dir = self.root / "run-canonical"
        target_dir = campaign_dir / "read-a"
        target_dir.mkdir(parents=True)
        (target_dir / "manifest.json").write_text("{}", encoding="utf-8")

        artifacts = discover_artifacts(campaign_dir, "read-a")

        self.assertEqual("read-a/manifest.json", artifacts[0].path)

    def test_artifact_path_escape_is_rejected(self) -> None:
        store = CampaignStore(self.root)
        campaign_dir = self.root / "run-3"
        campaign_dir.mkdir()
        (campaign_dir / "campaign.json").write_text(
            json.dumps({"campaignId": "run-3", "status": "PASSED", "targets": [{"artifacts": [{"id": "escape", "path": "../secret"}]}]}),
            encoding="utf-8",
        )

        with self.assertRaises(ArtifactNotFoundError):
            store.resolve_artifact("run-3", "escape")


class EventBufferTest(unittest.TestCase):
    def test_sanitizer_redacts_bearer_and_named_secrets(self) -> None:
        line = "Authorization: Bearer abc.def ACCESS_TOKEN=topsecret password: hunter2 ok=true"

        sanitized = sanitize_line(line)

        self.assertNotIn("abc.def", sanitized)
        self.assertNotIn("topsecret", sanitized)
        self.assertNotIn("hunter2", sanitized)
        self.assertIn("ok=true", sanitized)

    def test_buffer_keeps_only_latest_thousand_sequenced_events(self) -> None:
        events = EventBuffer(limit=1000)
        for index in range(1005):
            events.publish(target="read-a", phase="measurement", status=RunStatus.RUNNING, line=str(index))

        snapshot = events.after(0)

        self.assertEqual(1000, len(snapshot))
        self.assertEqual(6, snapshot[0].sequence)
        self.assertEqual(1005, snapshot[-1].sequence)


if __name__ == "__main__":
    unittest.main()
