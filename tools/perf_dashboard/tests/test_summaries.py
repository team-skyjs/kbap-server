import json
import tempfile
import unittest
from pathlib import Path

from tools.perf_dashboard.models import JsonValue
from tools.perf_dashboard.summaries import read_summary


class SummaryThresholdTest(unittest.TestCase):
    def _read(self, metrics: dict[str, JsonValue], checks_fail: int = 0) -> bool | None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "summary.json"
            data = {"metrics": metrics, "root_group": {"checks": {"status is expected": {"passes": 10, "fails": checks_fail}}}}
            path.write_text(json.dumps({"metadata": {"target": "read-a"}, "data": data}), encoding="utf-8")
            summary = read_summary(path)
        self.assertIsNotNone(summary)
        return summary.thresholds_passed

    def test_all_real_metric_thresholds_pass(self) -> None:
        metrics = {
            "http_req_duration": {"values": {"p(95)": 12.5}, "thresholds": {"p(95)<300": {"ok": True}}},
            "http_req_failed": {"values": {"rate": 0.0}, "thresholds": {"rate<0.01": {"ok": True}}},
        }

        self.assertTrue(self._read(metrics))

    def test_failed_metric_threshold_overrides_passing_root_checks(self) -> None:
        metrics = {
            "checks": {"values": {"rate": 1.0}, "thresholds": {"rate>0.99": {"ok": True}}},
            "dropped_iterations": {"values": {"count": 3}, "thresholds": {"count==0": {"ok": False}}},
        }

        self.assertFalse(self._read(metrics, checks_fail=0))

    def test_missing_metric_thresholds_are_unknown(self) -> None:
        metrics = {"http_req_duration": {"values": {"p(95)": 12.5}}}

        self.assertIsNone(self._read(metrics, checks_fail=2))


if __name__ == "__main__":
    unittest.main()
