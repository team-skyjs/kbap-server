import os
import re
import subprocess
import threading
import uuid
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from pathlib import Path
from contextlib import AbstractContextManager, contextmanager
from typing import Final, Iterator

from .artifact_io import OpenedArtifact, open_artifact
from .artifacts import OpenedBundle, build_bundle, discover_artifacts, open_bundle
from .events import CampaignEvent, EventBuffer, sanitize_line
from .identifiers import InvalidCampaignIdError, parse_campaign_id
from .models import Campaign, CampaignId, CampaignTarget, RunRequest, RunStatus
from .processes import ProcessRegistry
from .store import CampaignNotFoundError, CampaignStore
from .summaries import read_summary
from .waits import CampaignWaitTimeoutError, CampaignWaits, TERMINAL_STATUSES


ACTIVE_STATUSES: Final = (RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.CANCELLING)
PHASE_PATTERN: Final = re.compile(r"\bphase=([a-z-]+)\b")


@dataclass(frozen=True, slots=True)
class ActiveCampaignError(Exception):
    campaign_id: str

    def __str__(self) -> str:
        return "active-campaign-exists"


@dataclass(frozen=True, slots=True)
class ProcessStreamError(Exception):
    campaign_id: str

    def __str__(self) -> str:
        return f"process-stream-unavailable:{self.campaign_id}"


@dataclass(frozen=True, slots=True)
class CampaignShutdownTimeoutError(Exception):
    campaign_ids: tuple[str, ...]

    def __str__(self) -> str:
        return f"campaign-shutdown-timeout:{','.join(self.campaign_ids)}"


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _new_campaign_id() -> CampaignId:
    timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S%fZ")
    return CampaignId(f"{timestamp}-{uuid.uuid4().hex[:8]}")


class CampaignController:
    def __init__(self, artifact_root: Path, endpoint_runner: Path, cancel_grace_seconds: float = 10.0, terminate_grace_seconds: float = 2.0) -> None:
        self.store = CampaignStore(artifact_root)
        self.endpoint_runner = endpoint_runner
        self._condition = threading.Condition(threading.RLock())
        self._campaigns: dict[str, Campaign] = {}
        self._events: dict[str, EventBuffer] = {}
        self._cancel_requested: set[str] = set()
        self._processes = ProcessRegistry(cancel_grace_seconds, terminate_grace_seconds)
        self._bundle_lock = threading.Lock()
        self._waits = CampaignWaits(self._condition, self._campaigns, self._events, self._known_campaign_id)
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
        campaign_id = self._known_campaign_id(campaign_id)
        with self._condition:
            campaign = self._campaigns.get(campaign_id)
            if campaign is None:
                raise CampaignNotFoundError(campaign_id)
            return campaign

    def list(self) -> tuple[Campaign, ...]:
        with self._condition:
            return tuple(sorted(self._campaigns.values(), key=lambda item: item.created_at, reverse=True))

    def events(self, campaign_id: str) -> EventBuffer:
        campaign_id = self._known_campaign_id(campaign_id)
        with self._condition:
            events = self._events.get(campaign_id)
            if events is None:
                raise CampaignNotFoundError(campaign_id)
            return events

    def wait_for_status(self, campaign_id: str, status: RunStatus, timeout: float) -> Campaign:
        return self._waits.for_status(campaign_id, status, timeout)

    def wait_for_terminal(self, campaign_id: str, timeout: float) -> Campaign:
        return self._waits.for_terminal(campaign_id, timeout)

    def wait_for_event(self, campaign_id: str, line_fragment: str, timeout: float) -> CampaignEvent:
        return self._waits.for_event(campaign_id, line_fragment, timeout)

    def cancel(self, campaign_id: str) -> bool:
        campaign_id = self._known_campaign_id(campaign_id)
        with self._condition:
            campaign = self._campaigns.get(campaign_id)
            if campaign is None:
                raise CampaignNotFoundError(campaign_id)
            if campaign.status not in ACTIVE_STATUSES:
                return False
            if campaign.status is RunStatus.CANCELLING:
                return True
            self._cancel_requested.add(campaign_id)
            cancelling = replace(campaign, status=RunStatus.CANCELLING)
            self._save_locked(cancelling)
            self._events[campaign_id].publish("", "cancel", RunStatus.CANCELLING, "cancellation-requested")
        self._processes.cancel(campaign_id)
        return True

    def shutdown_active(self) -> None:
        with self._condition:
            active_ids = tuple(key for key, campaign in self._campaigns.items() if campaign.status in ACTIVE_STATUSES)
        for campaign_id in active_ids:
            self.cancel(campaign_id)
        if not active_ids:
            return
        with self._condition:
            finished = self._condition.wait_for(
                lambda: all(self._campaigns[campaign_id].status in TERMINAL_STATUSES for campaign_id in active_ids),
                timeout=self._processes.shutdown_budget,
            )
        if finished:
            return
        for campaign_id in active_ids:
            self._processes.kill(campaign_id)
        with self._condition:
            killed = self._condition.wait_for(
                lambda: all(self._campaigns[campaign_id].status in TERMINAL_STATUSES for campaign_id in active_ids),
                timeout=2.0,
            )
        if not killed:
            raise CampaignShutdownTimeoutError(active_ids)

    def resolve_artifact(self, campaign_id: str, artifact_id: str) -> Path:
        """Compatibility path resolver; HTTP serving must use open_artifact()."""
        campaign_id = self._known_campaign_id(campaign_id)
        return self.store.resolve_artifact(campaign_id, artifact_id)

    def open_artifact(self, campaign_id: str, artifact_id: str) -> AbstractContextManager[OpenedArtifact]:
        campaign_id = self._known_campaign_id(campaign_id)
        return open_artifact(self.store.root, campaign_id, artifact_id)

    def bundle(self, campaign_id: str) -> Path:
        """Compatibility path helper; HTTP serving must use open_bundle()."""
        campaign_id = self._known_campaign_id(campaign_id)
        self.get(campaign_id)
        with self._bundle_lock:
            return build_bundle(self.store.root / campaign_id)

    def open_bundle(self, campaign_id: str) -> AbstractContextManager[OpenedBundle]:
        campaign_id = self._known_campaign_id(campaign_id)
        self.get(campaign_id)
        return self._open_bundle_locked(campaign_id)

    @contextmanager
    def _open_bundle_locked(self, campaign_id: str) -> Iterator[OpenedBundle]:
        with self._bundle_lock, open_bundle(self.store.root / campaign_id) as opened:
            yield opened

    def _known_campaign_id(self, campaign_id: str) -> str:
        try:
            return str(parse_campaign_id(campaign_id))
        except InvalidCampaignIdError as error:
            raise CampaignNotFoundError(campaign_id) from error

    def _save_locked(self, campaign: Campaign) -> None:
        key = str(campaign.campaign_id)
        self.store.save(campaign)
        self._campaigns[key] = campaign
        self._condition.notify_all()

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
                self._processes.register(campaign_id, process, done)
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
                self._processes.complete(campaign_id)
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
