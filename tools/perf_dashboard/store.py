import json
import os
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from pathlib import Path

from .artifacts import ArtifactNotFoundError
from .models import Artifact, ArtifactId, Campaign, CampaignId, CampaignTarget, JsonValue, Profile, RunStatus, SummaryMetrics, campaign_document


@dataclass(frozen=True, slots=True)
class CampaignNotFoundError(Exception):
    campaign_id: str

    def __str__(self) -> str:
        return f"campaign-not-found:{self.campaign_id}"


@dataclass(frozen=True, slots=True)
class CampaignStoreError(Exception):
    path: Path

    def __str__(self) -> str:
        return f"campaign-store-error:{self.path}"


def _mapping(value: JsonValue) -> dict[str, JsonValue]:
    return value if isinstance(value, dict) else {}


def _string(document: dict[str, JsonValue], name: str, default: str = "") -> str:
    value = document.get(name)
    return value if isinstance(value, str) else default


def _optional_string(document: dict[str, JsonValue], name: str) -> str | None:
    value = document.get(name)
    return value if isinstance(value, str) else None


def _integer(document: dict[str, JsonValue], name: str, default: int = 0) -> int:
    value = document.get(name)
    return value if isinstance(value, int) and not isinstance(value, bool) else default


def _optional_integer(document: dict[str, JsonValue], name: str) -> int | None:
    value = document.get(name)
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _optional_number(document: dict[str, JsonValue], name: str) -> float | None:
    value = document.get(name)
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def _status(document: dict[str, JsonValue], default: RunStatus = RunStatus.QUEUED) -> RunStatus:
    try:
        return RunStatus(_string(document, "status", default.value))
    except ValueError:
        return default


def _summary(raw: JsonValue) -> SummaryMetrics | None:
    if not isinstance(raw, dict):
        return None
    threshold = raw.get("thresholdsPassed")
    return SummaryMetrics(
        p95=_optional_number(raw, "p95"),
        p99=_optional_number(raw, "p99"),
        failure_rate=_optional_number(raw, "failureRate"),
        dropped_iterations=_optional_number(raw, "droppedIterations"),
        thresholds_passed=threshold if isinstance(threshold, bool) else None,
    )


def _artifacts(raw: JsonValue) -> tuple[Artifact, ...]:
    if not isinstance(raw, list):
        return ()
    result: list[Artifact] = []
    for item in raw:
        document = _mapping(item)
        artifact_id = _string(document, "id")
        path = _string(document, "path")
        if artifact_id and path:
            result.append(Artifact(ArtifactId(artifact_id), _string(document, "name"), path, _string(document, "mediaType")))
    return tuple(result)


def _targets(raw: JsonValue) -> tuple[CampaignTarget, ...]:
    if not isinstance(raw, list):
        return ()
    result: list[CampaignTarget] = []
    for item in raw:
        document = _mapping(item)
        result.append(CampaignTarget(
            key=_string(document, "key"),
            status=_status(document),
            started_at=_optional_string(document, "startedAt"),
            finished_at=_optional_string(document, "finishedAt"),
            exit_code=_optional_integer(document, "exitCode"),
            summary=_summary(document.get("summary")),
            artifacts=_artifacts(document.get("artifacts")),
        ))
    return tuple(result)


def campaign_from_document(raw: JsonValue) -> Campaign:
    document = _mapping(raw)
    try:
        profile = Profile(_string(document, "profile", Profile.SMOKE.value))
    except ValueError:
        profile = Profile.SMOKE
    jfr_enabled = document.get("jfrEnabled")
    return Campaign(
        campaign_id=CampaignId(_string(document, "campaignId")),
        status=_status(document),
        profile=profile,
        rate_or_vus=_integer(document, "rateOrVus", 1),
        duration_or_iterations=_string(document, "durationOrIterations", "1"),
        jfr_enabled=jfr_enabled if isinstance(jfr_enabled, bool) else True,
        targets=_targets(document.get("targets")),
        created_at=_string(document, "createdAt"),
        started_at=_optional_string(document, "startedAt"),
        finished_at=_optional_string(document, "finishedAt"),
        failure_reason=_optional_string(document, "failureReason"),
    )


class CampaignStore:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)

    def save(self, campaign: Campaign) -> None:
        campaign_dir = self.root / str(campaign.campaign_id)
        campaign_dir.mkdir(parents=True, exist_ok=True)
        destination = campaign_dir / "campaign.json"
        temporary = campaign_dir / ".campaign.json.tmp"
        try:
            with temporary.open("w", encoding="utf-8") as output:
                json.dump(campaign_document(campaign), output, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)

    def load(self, campaign_id: str) -> Campaign:
        path = self.root / campaign_id / "campaign.json"
        try:
            raw: JsonValue = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError as error:
            raise CampaignNotFoundError(campaign_id) from error
        except (OSError, json.JSONDecodeError) as error:
            raise CampaignStoreError(path) from error
        campaign = campaign_from_document(raw)
        if str(campaign.campaign_id) != campaign_id:
            raise CampaignStoreError(path)
        return campaign

    def list(self) -> tuple[Campaign, ...]:
        campaigns: list[Campaign] = []
        for path in sorted(self.root.glob("*/campaign.json"), reverse=True):
            campaigns.append(self.load(path.parent.name))
        return tuple(campaigns)

    def recover_interrupted(self) -> tuple[Campaign, ...]:
        recovered: list[Campaign] = []
        for campaign in self.list():
            if campaign.status not in (RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.CANCELLING):
                continue
            finished_at = datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
            target_states = tuple(
                replace(
                    target,
                    status=RunStatus.FAILED if target.status is RunStatus.RUNNING else RunStatus.CANCELLED,
                    finished_at=target.finished_at or finished_at,
                )
                for target in campaign.targets
            )
            failed = replace(
                campaign,
                status=RunStatus.FAILED,
                targets=target_states,
                finished_at=finished_at,
                failure_reason="control-server-restarted",
            )
            self.save(failed)
            recovered.append(failed)
        return tuple(recovered)

    def resolve_artifact(self, campaign_id: str, artifact_id: str) -> Path:
        campaign = self.load(campaign_id)
        matching = tuple(
            artifact
            for target in campaign.targets
            for artifact in target.artifacts
            if str(artifact.id) == artifact_id
        )
        if len(matching) != 1:
            raise ArtifactNotFoundError(artifact_id)
        campaign_dir = (self.root / campaign_id).resolve()
        resolved = (campaign_dir / matching[0].path).resolve()
        if not resolved.is_relative_to(campaign_dir) or not resolved.is_file():
            raise ArtifactNotFoundError(artifact_id)
        return resolved
