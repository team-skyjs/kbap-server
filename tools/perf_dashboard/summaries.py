import json
from pathlib import Path

from .models import JsonValue, SummaryMetrics


def _mapping(value: JsonValue) -> dict[str, JsonValue]:
    return value if isinstance(value, dict) else {}


def _number(value: JsonValue) -> float | None:
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def _metric(metrics: dict[str, JsonValue], metric_name: str, value_name: str) -> float | None:
    metric = _mapping(metrics.get(metric_name))
    values = _mapping(metric.get("values"))
    return _number(values.get(value_name))


def _thresholds_passed(document: dict[str, JsonValue]) -> bool | None:
    root_group = _mapping(document.get("root_group"))
    raw_checks = root_group.get("checks")
    if not isinstance(raw_checks, list) or not raw_checks:
        return None
    failures: list[int] = []
    for raw in raw_checks:
        check = _mapping(raw)
        value = check.get("fails")
        if isinstance(value, int) and not isinstance(value, bool):
            failures.append(value)
    return all(value == 0 for value in failures) if failures else None


def read_summary(path: Path) -> SummaryMetrics | None:
    try:
        raw: JsonValue = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    document = _mapping(raw)
    data = _mapping(document.get("data"))
    metrics = _mapping(data.get("metrics"))
    return SummaryMetrics(
        p95=_metric(metrics, "http_req_duration", "p(95)"),
        p99=_metric(metrics, "http_req_duration", "p(99)"),
        failure_rate=_metric(metrics, "http_req_failed", "rate"),
        dropped_iterations=_metric(metrics, "dropped_iterations", "count"),
        thresholds_passed=_thresholds_passed(data),
    )
