import re
import threading
from collections import deque
from dataclasses import dataclass
from typing import Final

from .models import JsonValue, RunStatus


BEARER_PATTERN: Final = re.compile(r"(?i)(Bearer\s+)\S+")
JSON_VALUE_PATTERN: Final = re.compile(r'(?i)("(?P<key>[A-Za-z0-9_-]+)"\s*:\s*)"(?:\\.|[^"\\])*"')
ENV_VALUE_PATTERN: Final = re.compile(
    r"(?i)\b(?P<key>[A-Za-z_][A-Za-z0-9_-]*)(?P<separator>\s*(?:=|:)\s*)(?P<value>\"(?:\\.|[^\"\\])*\"|'[^']*'|[^\s,;]+)"
)
SECRET_COMPONENTS: Final = frozenset(("secret", "token", "key", "password"))


def _is_secret_name(name: str) -> bool:
    normalized = name.casefold()
    return normalized == "authorization" or not SECRET_COMPONENTS.isdisjoint(re.split(r"[_-]", normalized))


def _redact_json(match: re.Match[str]) -> str:
    return f'{match.group(1)}"[REDACTED]"' if _is_secret_name(match.group("key")) else match.group(0)


def _redact_env(match: re.Match[str]) -> str:
    return f'{match.group("key")}{match.group("separator")}[REDACTED]' if _is_secret_name(match.group("key")) else match.group(0)


def sanitize_line(line: str) -> str:
    without_bearer = BEARER_PATTERN.sub(r"\1[REDACTED]", line.rstrip("\r\n"))
    without_json = JSON_VALUE_PATTERN.sub(_redact_json, without_bearer)
    return ENV_VALUE_PATTERN.sub(_redact_env, without_json)


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
