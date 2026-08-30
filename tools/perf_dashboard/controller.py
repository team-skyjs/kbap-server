import os
import re
import signal
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from pathlib import Path
from typing import Final

from .artifacts import build_bundle, discover_artifacts
from .events import CampaignEvent, EventBuffer, sanitize_line
from .models import Campaign, CampaignId, CampaignTarget, RunRequest, RunStatus
from .store import CampaignNotFoundError, CampaignStore
from .summaries import read_summary


ACTIVE_STATUSES: Final = (RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.CANCELLING)
TERMINAL_STATUSES: Final = (RunStatus.PASSED, RunStatus.FAILED, RunStatus.CANCELLED)
PHASE_PATTERN: Final = re.compile(r"\bphase=([a-z-]+)\b")


@dataclass(frozen=True, slots=True)
class ActiveCampaignError(Exception):
    campaign_id: str

    def __str__(self) -> str:
        return "active-campaign-exists"


@dataclass(frozen=True, slots=True)
class CampaignWaitTimeoutError(Exception):
    campaign_id: str
    expected_status: str

    def __str__(self) -> str:
        return f"campaign-wait-timeout:{self.campaign_id}:{self.expected_status}"


@dataclass(frozen=True, slots=True)
class ProcessStreamError(Exception):
    campaign_id: str

    def __str__(self) -> str:
        return f"process-stream-unavailable:{self.campaign_id}"


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _new_campaign_id() -> CampaignId:
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S%fZ")
    return CampaignId(f"{timestamp}-{uuid.uuid4().hex[:8]}")


class CampaignController:
    def __init__(self, artifact_root: Path, endpoint_runner: Path, cancel_grace_seconds: float = 10.0) -> None:
        self.store = CampaignStore(artifact_root)
        self.endpoint_runner = endpoint_runner
        self.cancel_grace_seconds = cancel_grace_seconds
        self._condition = threading.Condition(threading.RLock())
        self._campaigns: dict[str, Campaign] = {}
        self._events: dict[str, EventBuffer] = {}
        self._cancel_requested: set[str] = set()
        self._processes: dict[str, subprocess.Popen[str]] = {}
        self._process_done: dict[str, threading.Event] = {}
        self._bundle_lock = threading.Lock()
        self.store.recover_interrupted()
        for campaign in self.store.list():
            campaign_id = str(campaign.campaign_id)
            self._campaigns[campaign_id] = campaign
            self._events[campaign_id] = EventBuffer()

    def start(self, request: RunRequest) -> Campaign:
        with self._condition:
            active = next((item for item in self._campaigns.values() if item.status in ACTIVE_STATUSES), None)
            if active is not None:
                raise ActiveCampaignError(str(active.campaign_id))
            campaign_id = _new_campaign_id()
            campaign = Campaign(
                campaign_id=campaign_id,
                status=RunStatus.QUEUED,
                profile=request.profile,
                rate_or_vus=request.rate_or_vus,
                duration_or_iterations=request.duration_or_iterations,
                jfr_enabled=request.jfr_enabled,
                targets=tuple(CampaignTarget(target.key, RunStatus.QUEUED) for target in request.targets),
                created_at=_now(),
            )
            key = str(campaign_id)
            self._campaigns[key] = campaign
            self._events[key] = EventBuffer()
            self.store.save(campaign)
        threading.Thread(target=self._run, args=(request, key), name=f"campaign-{key}", daemon=True).start()
        return campaign

    def get(self, campaign_id: str) -> Campaign:
        with self._condition:
            campaign = self._campaigns.get(campaign_id)
            if campaign is None:
                raise CampaignNotFoundError(campaign_id)
            return campaign

    def list(self) -> tuple[Campaign, ...]:
        with self._condition:
            return tuple(sorted(self._campaigns.values(), key=lambda item: item.created_at, reverse=True))

    def events(self, campaign_id: str) -> EventBuffer:
        with self._condition:
            events = self._events.get(campaign_id)
            if events is None:
                raise CampaignNotFoundError(campaign_id)
            return events

    def wait_for_status(self, campaign_id: str, status: RunStatus, timeout: float) -> Campaign:
        with self._condition:
            reached = self._condition.wait_for(
                lambda: campaign_id in self._campaigns and self._campaigns[campaign_id].status is status,
                timeout=timeout,
            )
            if not reached:
                raise CampaignWaitTimeoutError(campaign_id, status.value)
            return self._campaigns[campaign_id]

    def wait_for_terminal(self, campaign_id: str, timeout: float) -> Campaign:
        with self._condition:
            reached = self._condition.wait_for(
                lambda: campaign_id in self._campaigns and self._campaigns[campaign_id].status in TERMINAL_STATUSES,
                timeout=timeout,
            )
            if not reached:
                raise CampaignWaitTimeoutError(campaign_id, "terminal")
            return self._campaigns[campaign_id]

    def wait_for_event(self, campaign_id: str, line_fragment: str, timeout: float) -> CampaignEvent:
        events = self.events(campaign_id)
        sequence = 0
        deadline = time.monotonic() + timeout
        while deadline > time.monotonic():
            available = events.wait_after(sequence, deadline - time.monotonic())
            for event in available:
                if line_fragment in event.line:
                    return event
                sequence = event.sequence
        raise CampaignWaitTimeoutError(campaign_id, f"event:{line_fragment}")

    def cancel(self, campaign_id: str) -> bool:
        with self._condition:
            campaign = self._campaigns.get(campaign_id)
            if campaign is None or campaign.status not in ACTIVE_STATUSES:
                return False
            self._cancel_requested.add(campaign_id)
            cancelling = replace(campaign, status=RunStatus.CANCELLING)
            self._save_locked(cancelling)
            self._events[campaign_id].publish("", "cancel", RunStatus.CANCELLING, "cancellation-requested")
            process = self._processes.get(campaign_id)
            done = self._process_done.get(campaign_id)
        if process is not None and done is not None:
            self._signal_process(process, signal.SIGINT)
            threading.Thread(target=self._escalate, args=(process, done), daemon=True).start()
        return True

    def resolve_artifact(self, campaign_id: str, artifact_id: str) -> Path:
        return self.store.resolve_artifact(campaign_id, artifact_id)

    def bundle(self, campaign_id: str) -> Path:
        self.get(campaign_id)
        with self._bundle_lock:
            return build_bundle(self.store.root / campaign_id)

    def _save_locked(self, campaign: Campaign) -> None:
        key = str(campaign.campaign_id)
        self.store.save(campaign)
        self._campaigns[key] = campaign
        self._condition.notify_all()

    def _signal_process(self, process: subprocess.Popen[str], selected_signal: signal.Signals) -> None:
        try:
            os.killpg(process.pid, selected_signal)
        except ProcessLookupError:
            return

    def _escalate(self, process: subprocess.Popen[str], done: threading.Event) -> None:
        if not done.wait(self.cancel_grace_seconds):
            self._signal_process(process, signal.SIGTERM)

    def _run(self, request: RunRequest, campaign_id: str) -> None:
        for index, target in enumerate(request.targets):
            if not self._run_target(request, campaign_id, index, target.key):
                return
            with self._condition:
                if campaign_id in self._cancel_requested:
                    self._finish_cancelled_locked(campaign_id, index + 1)
                    return
        with self._condition:
            campaign = self._campaigns[campaign_id]
            final_status = RunStatus.FAILED if any(item.status is RunStatus.FAILED for item in campaign.targets) else RunStatus.PASSED
            finished = replace(campaign, status=final_status, finished_at=_now())
            self._save_locked(finished)
            self._events[campaign_id].publish("", "campaign", final_status, "campaign-finished")

    def _run_target(self, request: RunRequest, campaign_id: str, index: int, target_key: str) -> bool:
        argv = [str(self.endpoint_runner), target_key, request.profile.value, str(request.rate_or_vus), request.duration_or_iterations]
        env = os.environ.copy()
        env["CAMPAIGN_ID"] = campaign_id
        env["JFR_ENABLED"] = "true" if request.jfr_enabled else "false"
        done = threading.Event()
        process: subprocess.Popen[str] | None = None
        try:
            with self._condition:
                if campaign_id in self._cancel_requested:
                    self._finish_cancelled_locked(campaign_id, index)
                    return False
                process = subprocess.Popen(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True, shell=False)
                self._processes[campaign_id] = process
                self._process_done[campaign_id] = done
                campaign = self._campaigns[campaign_id]
                targets = list(campaign.targets)
                targets[index] = replace(targets[index], status=RunStatus.RUNNING, started_at=_now())
                running = replace(campaign, status=RunStatus.RUNNING, started_at=campaign.started_at or _now(), targets=tuple(targets))
                self._save_locked(running)
                self._events[campaign_id].publish(target_key, "start", RunStatus.RUNNING, "target-started")
            with process:
                if process.stdout is None:
                    raise ProcessStreamError(campaign_id)
                for line in process.stdout:
                    phase_match = PHASE_PATTERN.search(line)
                    phase = phase_match.group(1) if phase_match is not None else "console"
                    safe_line = sanitize_line(line)
                    self._append_log(campaign_id, safe_line)
                    with self._condition:
                        event_status = self._campaigns[campaign_id].status
                    self._events[campaign_id].publish(target_key, phase, event_status, safe_line)
                exit_code = process.wait()
        except OSError:
            exit_code = 127
        finally:
            done.set()
            if process is not None:
                with self._condition:
                    self._processes.pop(campaign_id, None)
                    self._process_done.pop(campaign_id, None)
        with self._condition:
            campaign = self._campaigns[campaign_id]
            targets = list(campaign.targets)
            cancelled = campaign_id in self._cancel_requested
            status = RunStatus.CANCELLED if cancelled else (RunStatus.PASSED if exit_code == 0 else RunStatus.FAILED)
            campaign_dir = self.store.root / campaign_id
            artifacts = discover_artifacts(campaign_dir, target_key)
            summary = read_summary(campaign_dir / target_key / "summary.json")
            targets[index] = replace(targets[index], status=status, finished_at=_now(), exit_code=exit_code, summary=summary, artifacts=artifacts)
            updated = replace(campaign, targets=tuple(targets))
            self._save_locked(updated)
            self._events[campaign_id].publish(target_key, "finish", status, "target-finished")
        return True

    def _finish_cancelled_locked(self, campaign_id: str, start_index: int) -> None:
        campaign = self._campaigns[campaign_id]
        targets = list(campaign.targets)
        for index in range(start_index, len(targets)):
            targets[index] = replace(targets[index], status=RunStatus.CANCELLED, finished_at=_now())
        cancelled = replace(campaign, status=RunStatus.CANCELLED, targets=tuple(targets), finished_at=_now())
        self._save_locked(cancelled)
        self._events[campaign_id].publish("", "campaign", RunStatus.CANCELLED, "campaign-cancelled")

    def _append_log(self, campaign_id: str, line: str) -> None:
        with (self.store.root / campaign_id / "campaign.log").open("a", encoding="utf-8") as output:
            output.write(line + "\n")
