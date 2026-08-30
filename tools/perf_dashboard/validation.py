import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Final, assert_never

from .models import JsonValue, Profile, Risk, RunRequest, SelectionMode, Suite, Target


@dataclass(frozen=True, slots=True)
class RequestValidationError(Exception):
    code: str

    def __str__(self) -> str:
        return self.code


@dataclass(frozen=True, slots=True)
class TargetManifestError(Exception):
    path: Path

    def __str__(self) -> str:
        return f"invalid-target-manifest:{self.path}"


@dataclass(frozen=True, slots=True)
class ProfileLimit:
    max_rate_or_vus: int
    max_seconds_or_iterations: int


PROFILE_LIMITS: Final[Mapping[Profile, ProfileLimit]] = {
    Profile.SMOKE: ProfileLimit(1, 1),
    Profile.READ: ProfileLimit(40, 300),
    Profile.WRITE: ProfileLimit(10, 120),
    Profile.EXTERNAL: ProfileLimit(10, 10),
}
KEY_PATTERN: Final = re.compile(r"^[A-Za-z0-9._-]+$")
DURATION_PATTERN: Final = re.compile(r"^([1-9][0-9]*)([sm])$")
ITERATION_PATTERN: Final = re.compile(r"^[1-9][0-9]*$")
MAX_NUMERIC_DIGITS: Final = 6


def _suite_profile(suite: Suite) -> Profile:
    match suite:
        case Suite.READ:
            return Profile.READ
        case Suite.REVERSIBLE_WRITE | Suite.FIXTURE_WRITE:
            return Profile.WRITE
        case Suite.EXTERNAL:
            return Profile.EXTERNAL
        case unreachable:
            assert_never(unreachable)


def _string(entry: Mapping[str, JsonValue], name: str, path: Path) -> str:
    value = entry.get(name)
    if not isinstance(value, str) or not value:
        raise TargetManifestError(path)
    return value


def _parse_target(raw: JsonValue, path: Path) -> Target:
    if not isinstance(raw, dict):
        raise TargetManifestError(path)
    key = _string(raw, "key", path)
    default_enabled = raw.get("defaultEnabled")
    if not KEY_PATTERN.fullmatch(key) or not isinstance(default_enabled, bool):
        raise TargetManifestError(path)
    try:
        suite = Suite(_string(raw, "suite", path))
        risk = Risk(_string(raw, "risk", path))
        profile = Profile(_string(raw, "defaultProfile", path))
    except ValueError as error:
        raise TargetManifestError(path) from error
    if profile is not _suite_profile(suite):
        raise TargetManifestError(path)
    return Target(
        key=key,
        label=_string(raw, "label", path),
        method=_string(raw, "method", path),
        route=_string(raw, "route", path),
        suite=suite,
        risk=risk,
        default_profile=profile,
        default_enabled=default_enabled,
    )


def load_targets(path: Path) -> tuple[Target, ...]:
    try:
        document: JsonValue = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise TargetManifestError(path) from error
    if not isinstance(document, dict):
        raise TargetManifestError(path)
    entries = document.get("targets")
    if not isinstance(entries, list) or not entries:
        raise TargetManifestError(path)
    targets = tuple(_parse_target(entry, path) for entry in entries)
    if len({target.key for target in targets}) != len(targets):
        raise TargetManifestError(path)
    return targets


def _profile(payload: Mapping[str, JsonValue]) -> Profile:
    raw = payload.get("profile")
    if not isinstance(raw, str):
        raise RequestValidationError("invalid-profile")
    try:
        return Profile(raw)
    except ValueError as error:
        raise RequestValidationError("invalid-profile") from error


def _selection_mode(payload: Mapping[str, JsonValue]) -> SelectionMode:
    raw = payload.get("mode")
    if not isinstance(raw, str):
        raise RequestValidationError("invalid-mode")
    try:
        return SelectionMode(raw)
    except ValueError as error:
        raise RequestValidationError("invalid-mode") from error


def _selected_targets(payload: Mapping[str, JsonValue], targets: tuple[Target, ...]) -> tuple[Target, ...]:
    mode = _selection_mode(payload)
    keys: set[str]
    match mode:
        case SelectionMode.SAFE_ALL:
            return tuple(target for target in targets if target.default_enabled and target.risk is Risk.SAFE)
        case SelectionMode.SUITE:
            raw_suite = payload.get("suite")
            if not isinstance(raw_suite, str):
                raise RequestValidationError("invalid-suite")
            try:
                suite = Suite(raw_suite)
            except ValueError as error:
                raise RequestValidationError("invalid-suite") from error
            return tuple(target for target in targets if target.suite is suite)
        case SelectionMode.SINGLE:
            raw_key = payload.get("targetKey")
            if not isinstance(raw_key, str):
                raise RequestValidationError("invalid-target")
            keys = {raw_key}
        case SelectionMode.SELECTED | SelectionMode.TARGETS:
            raw_keys = payload.get("targetKeys")
            if not isinstance(raw_keys, list) or not raw_keys or not all(isinstance(key, str) for key in raw_keys):
                raise RequestValidationError("invalid-target")
            keys = set(raw_keys)
            if len(keys) != len(raw_keys):
                raise RequestValidationError("invalid-target")
        case unreachable:
            assert_never(unreachable)
    selected = tuple(target for target in targets if target.key in keys)
    if len(selected) != len(keys):
        raise RequestValidationError("invalid-target")
    return selected


def _positive_rate(raw: JsonValue, limit: ProfileLimit) -> int:
    if isinstance(raw, bool) or not isinstance(raw, int) or raw < 1 or raw > limit.max_rate_or_vus:
        raise RequestValidationError("invalid-rate-or-vus")
    return raw


def _duration(raw: JsonValue, profile: Profile, limit: ProfileLimit) -> str:
    if not isinstance(raw, str):
        raise RequestValidationError("invalid-duration-or-iterations")
    match profile:
        case Profile.SMOKE | Profile.EXTERNAL:
            if len(raw) > MAX_NUMERIC_DIGITS or not ITERATION_PATTERN.fullmatch(raw) or int(raw) > limit.max_seconds_or_iterations:
                raise RequestValidationError("invalid-duration-or-iterations")
        case Profile.READ | Profile.WRITE:
            matched = DURATION_PATTERN.fullmatch(raw)
            if matched is None:
                raise RequestValidationError("invalid-duration-or-iterations")
            amount = matched.group(1)
            if len(amount) > MAX_NUMERIC_DIGITS:
                raise RequestValidationError("invalid-duration-or-iterations")
            seconds = int(amount) * (60 if matched.group(2) == "m" else 1)
            if seconds > limit.max_seconds_or_iterations:
                raise RequestValidationError("invalid-duration-or-iterations")
        case unreachable:
            assert_never(unreachable)
    return raw


def validate_run_request(payload: Mapping[str, JsonValue], targets: tuple[Target, ...]) -> RunRequest:
    selected = _selected_targets(payload, targets)
    if not selected:
        raise RequestValidationError("empty-target-selection")
    profile = _profile(payload)
    compatible = profile is Profile.SMOKE or all(
        target.default_profile is profile and _suite_profile(target.suite) is profile
        for target in selected
    )
    if not compatible:
        raise RequestValidationError("profile-target-mismatch")
    allow_risk = payload.get("allowRisk", False)
    if not isinstance(allow_risk, bool):
        raise RequestValidationError("invalid-allow-risk")
    if any(target.risk is not Risk.SAFE for target in selected) and not allow_risk:
        raise RequestValidationError("risk-approval-required")
    raw_jfr = payload.get("jfrEnabled", True)
    if not isinstance(raw_jfr, bool):
        raise RequestValidationError("invalid-jfr-enabled")
    if not raw_jfr and (profile is not Profile.SMOKE or len(selected) != 1):
        raise RequestValidationError("jfr-off-requires-single-smoke")
    limit = PROFILE_LIMITS[profile]
    return RunRequest(
        targets=selected,
        profile=profile,
        rate_or_vus=_positive_rate(payload.get("rateOrVus"), limit),
        duration_or_iterations=_duration(payload.get("durationOrIterations"), profile, limit),
        jfr_enabled=raw_jfr,
    )
