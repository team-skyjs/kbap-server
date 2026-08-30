import json
import re
import threading
from collections import deque
from dataclasses import dataclass
from typing import Final

from .models import JsonValue, RunStatus


BEARER_PATTERN: Final = re.compile(r"(?i)(Bearer\s+)\S+")
JSON_FRAGMENT_PATTERN: Final = re.compile(
    r'(?i)(?P<prefix>"(?P<key>[A-Za-z0-9_ -]+)"\s*:\s*)(?P<value>"(?:\\.|[^"\\])*"|\[[^\]\r\n]*\]|\{[^}\r\n]*\}|true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:e[+-]?[0-9]+)?)'
)
ENV_VALUE_PATTERN: Final = re.compile(
    r"(?i)\b(?P<key>[A-Za-z_][A-Za-z0-9_-]*)(?P<separator>\s*(?:=|:)\s*)(?P<value>\"(?:\\.|[^\"\\])*\"|'[^']*'|\[[^\]\r\n]*\]|\{[^}\r\n]*\}|[^\s,;]+)"
)
SECRET_COMPONENTS: Final = frozenset(("token", "secret", "password", "key", "auth", "authorization", "authentication", "credential", "credentials"))
ACRONYM_BOUNDARY: Final = re.compile(r"([A-Z]+)([A-Z][a-z])")
CAMEL_BOUNDARY: Final = re.compile(r"([a-z0-9])([A-Z])")
NAME_COMPONENT: Final = re.compile(r"[A-Za-z0-9]+")


def _is_secret_name(name: str) -> bool:
    separated = ACRONYM_BOUNDARY.sub(r"\1 \2", name)
    separated = CAMEL_BOUNDARY.sub(r"\1 \2", separated)
    components = frozenset(match.group(0).casefold() for match in NAME_COMPONENT.finditer(separated))
    return not SECRET_COMPONENTS.isdisjoint(components)


def _redact_json(value: JsonValue) -> JsonValue:
    if isinstance(value, dict):
        return {key: "[REDACTED]" if _is_secret_name(key) else _redact_json(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_redact_json(item) for item in value]
    if isinstance(value, str):
        return BEARER_PATTERN.sub(r"\1[REDACTED]", value)
    return value


def _redact_env(match: re.Match[str]) -> str:
    return f'{match.group("key")}{match.group("separator")}[REDACTED]' if _is_secret_name(match.group("key")) else match.group(0)


def _redact_json_fragment(match: re.Match[str]) -> str:
    return f'{match.group("prefix")}"[REDACTED]"' if _is_secret_name(match.group("key")) else match.group(0)


def sanitize_line(line: str) -> str:
    stripped = line.rstrip("\r\n")
    try:
        document: JsonValue = json.loads(stripped)
    except json.JSONDecodeError:
        without_bearer = BEARER_PATTERN.sub(r"\1[REDACTED]", stripped)
        without_fragments = JSON_FRAGMENT_PATTERN.sub(_redact_json_fragment, without_bearer)
        return ENV_VALUE_PATTERN.sub(_redact_env, without_fragments)
    return json.dumps(_redact_json(document), ensure_ascii=False, separators=(",", ":"))


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
