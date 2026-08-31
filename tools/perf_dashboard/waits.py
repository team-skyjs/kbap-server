import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass

from .events import CampaignEvent, EventBuffer
from .models import Campaign, RunStatus


TERMINAL_STATUSES = (RunStatus.PASSED, RunStatus.FAILED, RunStatus.CANCELLED)


@dataclass(frozen=True, slots=True)
class CampaignWaitTimeoutError(Exception):
    campaign_id: str
    expected_status: str

    def __str__(self) -> str:
        return f"campaign-wait-timeout:{self.campaign_id}:{self.expected_status}"


class CampaignWaits:
    def __init__(
        self,
        condition: threading.Condition,
        campaigns: Mapping[str, Campaign],
        events: Mapping[str, EventBuffer],
        normalize: Callable[[str], str],
    ) -> None:
        self._condition = condition
        self._campaigns = campaigns
        self._events = events
        self._normalize = normalize

    def for_status(self, raw_campaign_id: str, status: RunStatus, timeout: float) -> Campaign:
        campaign_id = self._normalize(raw_campaign_id)
        with self._condition:
            reached = self._condition.wait_for(
                lambda: campaign_id in self._campaigns and self._campaigns[campaign_id].status is status,
                timeout=timeout,
            )
            if not reached:
                raise CampaignWaitTimeoutError(campaign_id, status.value)
            return self._campaigns[campaign_id]

    def for_terminal(self, raw_campaign_id: str, timeout: float) -> Campaign:
        campaign_id = self._normalize(raw_campaign_id)
        with self._condition:
            reached = self._condition.wait_for(
                lambda: campaign_id in self._campaigns and self._campaigns[campaign_id].status in TERMINAL_STATUSES,
                timeout=timeout,
            )
            if not reached:
                raise CampaignWaitTimeoutError(campaign_id, "terminal")
            return self._campaigns[campaign_id]

    def for_event(self, raw_campaign_id: str, line_fragment: str, timeout: float) -> CampaignEvent:
        campaign_id = self._normalize(raw_campaign_id)
        events = self._events[campaign_id]
        sequence = 0
        deadline = time.monotonic() + timeout
        while deadline > time.monotonic():
            available = events.wait_after(sequence, deadline - time.monotonic())
            for event in available:
                if line_fragment in event.line:
                    return event
                sequence = event.sequence
        raise CampaignWaitTimeoutError(campaign_id, f"event:{line_fragment}")
