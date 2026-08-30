import re
import threading
from collections import deque
from dataclasses import dataclass
from typing import Final

from .models import JsonValue, RunStatus


BEARER_PATTERN: Final = re.compile(r"(?i)(Bearer\s+)\S+")
NAMED_SECRET_PATTERN: Final = re.compile(
    r"(?i)\b([A-Z0-9_]*(?:secret|token|key|password)[A-Z0-9_]*)(\s*(?:=|:)\s*|\s+)\S+"
)


def sanitize_line(line: str) -> str:
    without_bearer = BEARER_PATTERN.sub(r"\1[REDACTED]", line.rstrip("\r\n"))
    return NAMED_SECRET_PATTERN.sub(r"\1\2[REDACTED]", without_bearer)


@dataclass(frozen=True, slots=True)
class CampaignEvent:
    sequence: int
    target: str
    phase: str
    status: RunStatus
    line: str

    def document(self) -> JsonValue:
        return {
            "target": self.target,
            "phase": self.phase,
            "status": self.status.value,
            "line": self.line,
        }


class EventBuffer:
    def __init__(self, limit: int = 1000) -> None:
        self._events: deque[CampaignEvent] = deque(maxlen=limit)
        self._sequence = 0
        self._condition = threading.Condition()

    def publish(self, target: str, phase: str, status: RunStatus, line: str) -> CampaignEvent:
        with self._condition:
            self._sequence += 1
            event = CampaignEvent(self._sequence, target, phase, status, sanitize_line(line))
            self._events.append(event)
            self._condition.notify_all()
            return event

    def after(self, sequence: int) -> tuple[CampaignEvent, ...]:
        with self._condition:
            return tuple(event for event in self._events if event.sequence > sequence)

    def wait_after(self, sequence: int, timeout: float) -> tuple[CampaignEvent, ...]:
        with self._condition:
            self._condition.wait_for(lambda: self._sequence > sequence, timeout=timeout)
            return tuple(event for event in self._events if event.sequence > sequence)
