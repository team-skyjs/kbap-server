from dataclasses import dataclass
from pathlib import Path

from .controller import CampaignController
from .models import Campaign, RunRequest
from .validation import load_targets, validate_run_request


_controller: CampaignController | None = None


@dataclass(frozen=True, slots=True)
class ControllerNotConfiguredError(Exception):
    def __str__(self) -> str:
        return "campaign-controller-not-configured"


def configure_controller(controller: CampaignController) -> None:
    global _controller
    _controller = controller


def _configured_controller() -> CampaignController:
    if _controller is None:
        raise ControllerNotConfiguredError
    return _controller


def start_campaign(request: RunRequest) -> Campaign:
    return _configured_controller().start(request)


def cancel_campaign(campaign_id: str) -> bool:
    return _configured_controller().cancel(campaign_id)


def resolve_artifact(campaign_id: str, artifact_id: str) -> Path:
    return _configured_controller().resolve_artifact(campaign_id, artifact_id)


__all__ = [
    "cancel_campaign",
    "load_targets",
    "resolve_artifact",
    "start_campaign",
    "validate_run_request",
]
