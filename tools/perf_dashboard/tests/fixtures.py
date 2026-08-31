TARGETS = {
    "targets": [
        {"key": "read-a", "label": "Read A", "method": "GET", "route": "/a", "suite": "read", "risk": "safe", "defaultProfile": "read", "defaultEnabled": True, "requestsPerIteration": 1},
        {"key": "read-b", "label": "Read B", "method": "GET", "route": "/b", "suite": "read", "risk": "safe", "defaultProfile": "read", "defaultEnabled": True, "requestsPerIteration": 1},
        {"key": "write-a", "label": "Write A", "method": "POST", "route": "/write", "suite": "reversible-write", "risk": "safe", "defaultProfile": "write", "defaultEnabled": True, "requestsPerIteration": 1},
        {"key": "cost-a", "label": "Cost A", "method": "POST", "route": "/c", "suite": "external", "risk": "cost", "defaultProfile": "external", "defaultEnabled": False, "requestsPerIteration": 1},
    ]
}

FAKE_RUNNER = r'''#!/usr/bin/env python3
import json
import os
import signal
import sys
from pathlib import Path

target, profile, load, extent = sys.argv[1:]
root = Path(os.environ["PERFORMANCE_ARTIFACT_ROOT"])
campaign_id = os.environ["CAMPAIGN_ID"]
record = Path(os.environ["FAKE_RECORD"])
with record.open("a", encoding="utf-8") as output:
    output.write(json.dumps({"argv": [target, profile, load, extent], "envNames": sorted(os.environ), "pid": os.getpid(), "pgid": os.getpgrp()}) + "\n")

def artifacts():
    target_dir = root / campaign_id / target
    target_dir.mkdir(parents=True, exist_ok=True)
    (target_dir / "report.html").write_text("<h1>safe</h1>", encoding="utf-8")
    data = {"metrics": {"http_req_duration": {"values": {"p(95)": 12.5, "p(99)": 17.5}, "thresholds": {"p(95)<300": {"ok": True}, "p(99)<750": {"ok": True}}}, "http_req_failed": {"values": {"rate": 0.25}, "thresholds": {"rate<0.30": {"ok": True}}}, "dropped_iterations": {"values": {"count": 3}, "thresholds": {"count<4": {"ok": True}}}}, "root_group": {"checks": {"status is expected": {"passes": 1, "fails": 0}}}}
    (target_dir / "summary.json").write_text(json.dumps({"metadata": {"target": target}, "data": data}), encoding="utf-8")
    (target_dir / "manifest.json").write_text(json.dumps({"campaignId": campaign_id, "target": target, "taskIds": ["one", "two"]}), encoding="utf-8")
    if os.environ["JFR_ENABLED"] == "true":
        (target_dir / "task-one.jfr").write_bytes(b"jfr-one")
        (target_dir / "task-two.jfr").write_bytes(b"jfr-two")

def record_signal(signum):
    signal_record = os.environ.get("FAKE_SIGNAL_RECORD")
    if signal_record is not None:
        with Path(signal_record).open("a", encoding="utf-8") as output:
            output.write(f"{signum}\n")

def interrupt(signum, frame):
    record_signal(signum)
    print("phase=cleanup ACCESS_TOKEN=runner-secret", flush=True)
    Path(os.environ["FAKE_TRAP_ENTERED"]).write_text(str(signum), encoding="utf-8")

def terminate(signum, frame):
    record_signal(signum)
    if os.environ.get("FAKE_IGNORE_TERM") == "1":
        return
    artifacts()
    Path(os.environ["FAKE_TRAP_EXITED"]).write_text(str(signum), encoding="utf-8")
    raise SystemExit(130)

signal.signal(signal.SIGINT, interrupt)
signal.signal(signal.SIGTERM, terminate)
print("phase=measurement Authorization: Bearer runner-secret", flush=True)
if os.environ.get("FAKE_HOLD") == "1":
    while True:
        signal.pause()
else:
    artifacts()
    raise SystemExit(int(os.environ.get("FAKE_EXIT_CODE", "0")))
'''
