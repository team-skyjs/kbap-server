"""Dashboard API; resolve_artifact exposes path semantics, not a serving descriptor."""

from .campaign import cancel_campaign, load_targets, resolve_artifact, start_campaign, validate_run_request
from .models import Campaign, RunRequest, RunStatus, Target


__all__ = [
    "Campaign",
    "RunRequest",
    "RunStatus",
    "Target",
    "cancel_campaign",
    "load_targets",
    "resolve_artifact",
    "start_campaign",
    "validate_run_request",
]
