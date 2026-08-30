from dataclasses import dataclass
from enum import Enum
from typing import NewType, TypeAlias, TypedDict


JsonValue: TypeAlias = str | int | float | bool | None | list["JsonValue"] | dict[str, "JsonValue"]
CampaignId = NewType("CampaignId", str)
ArtifactId = NewType("ArtifactId", str)


class RunStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    CANCELLING = "CANCELLING"
    PASSED = "PASSED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class Profile(str, Enum):
    SMOKE = "smoke"
    READ = "read"
    WRITE = "write"
    EXTERNAL = "external"


class Risk(str, Enum):
    SAFE = "safe"
    FIXTURE = "fixture"
    COST = "cost"


class Suite(str, Enum):
    READ = "read"
    REVERSIBLE_WRITE = "reversible-write"
    FIXTURE_WRITE = "fixture-write"
    EXTERNAL = "external"


class SelectionMode(str, Enum):
    SAFE_ALL = "safe-all"
    SUITE = "suite"
    SINGLE = "single"
    SELECTED = "selected"
    TARGETS = "targets"


@dataclass(frozen=True, slots=True)
class Target:
    key: str
    label: str
    method: str
    route: str
    suite: Suite
    risk: Risk
    default_profile: Profile
    default_enabled: bool


@dataclass(frozen=True, slots=True)
class RunRequest:
    targets: tuple[Target, ...]
    profile: Profile
    rate_or_vus: int
    duration_or_iterations: str
    jfr_enabled: bool


@dataclass(frozen=True, slots=True)
class SummaryMetrics:
    p95: float | None
    p99: float | None
    failure_rate: float | None
    dropped_iterations: float | None
    thresholds_passed: bool | None


@dataclass(frozen=True, slots=True)
class Artifact:
    id: ArtifactId
    name: str
    path: str
    media_type: str


@dataclass(frozen=True, slots=True)
class CampaignTarget:
    key: str
    status: RunStatus
    started_at: str | None = None
    finished_at: str | None = None
    exit_code: int | None = None
    summary: SummaryMetrics | None = None
    artifacts: tuple[Artifact, ...] = ()


@dataclass(frozen=True, slots=True)
class Campaign:
    campaign_id: CampaignId
    status: RunStatus
    profile: Profile
    rate_or_vus: int
    duration_or_iterations: str
    jfr_enabled: bool
    targets: tuple[CampaignTarget, ...]
    created_at: str
    started_at: str | None = None
    finished_at: str | None = None
    failure_reason: str | None = None


class TargetApiDocument(TypedDict):
    key: str
    label: str
    method: str
    route: str
    suite: str
    risk: str
    defaultProfile: str
    defaultEnabled: bool


def target_api_document(target: Target) -> TargetApiDocument:
    return {
        "key": target.key,
        "label": target.label,
        "method": target.method,
        "route": target.route,
        "suite": target.suite.value,
        "risk": target.risk.value,
        "defaultProfile": target.default_profile.value,
        "defaultEnabled": target.default_enabled,
    }


def campaign_document(campaign: Campaign) -> JsonValue:
    targets: list[JsonValue] = []
    for target in campaign.targets:
        artifacts: list[JsonValue] = [
            {"id": str(item.id), "name": item.name, "path": item.path, "mediaType": item.media_type}
            for item in target.artifacts
        ]
        summary: JsonValue = None
        if target.summary is not None:
            summary = {
                "p95": target.summary.p95,
                "p99": target.summary.p99,
                "failureRate": target.summary.failure_rate,
                "droppedIterations": target.summary.dropped_iterations,
                "thresholdsPassed": target.summary.thresholds_passed,
            }
        targets.append({
            "key": target.key,
            "status": target.status.value,
            "startedAt": target.started_at,
            "finishedAt": target.finished_at,
            "exitCode": target.exit_code,
            "summary": summary,
            "artifacts": artifacts,
        })
    return {
        "campaignId": str(campaign.campaign_id),
        "status": campaign.status.value,
        "profile": campaign.profile.value,
        "rateOrVus": campaign.rate_or_vus,
        "durationOrIterations": campaign.duration_or_iterations,
        "jfrEnabled": campaign.jfr_enabled,
        "createdAt": campaign.created_at,
        "startedAt": campaign.started_at,
        "finishedAt": campaign.finished_at,
        "failureReason": campaign.failure_reason,
        "targets": targets,
    }
