from __future__ import annotations

from dataclasses import dataclass
import re


_ATTRIBUTE = re.compile(r'\[([a-zA-Z0-9_-]+)(?:=["\']?([^"\']+?)["\']?)?\]')
_CLASS = re.compile(r"\.([a-zA-Z0-9_-]+)")
_ID = re.compile(r"#([a-zA-Z0-9_-]+)")
_PSEUDO = re.compile(r"(?<!:):([a-zA-Z0-9_-]+)")
_TOKEN = re.compile(r"var\((--[a-z0-9-]+)\)")


@dataclass(frozen=True, slots=True)
class CssElement:
    tag: str
    classes: frozenset[str] = frozenset()
    attributes: tuple[tuple[str, str], ...] = ()
    states: frozenset[str] = frozenset()
    parent: CssElement | None = None


@dataclass(frozen=True, slots=True)
class _Winner:
    value: str
    important: bool
    specificity: tuple[int, int, int]
    order: int


def _rules(source: str) -> list[tuple[list[str], list[tuple[str, str, bool]]]]:
    clean = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    rules: list[tuple[list[str], list[tuple[str, str, bool]]]] = []
    for selector_text, body in re.findall(r"([^{}]+)\{([^{}]*)\}", clean):
        declarations: list[tuple[str, str, bool]] = []
        for declaration in body.split(";"):
            if ":" not in declaration:
                continue
            name, value = declaration.split(":", 1)
            normalized = value.strip()
            important = normalized.endswith("!important")
            if important:
                normalized = normalized.removesuffix("!important").strip()
            declarations.append((name.strip(), normalized, important))
        if declarations:
            rules.append(([selector.strip() for selector in selector_text.split(",")], declarations))
    return rules


def _specificity(selector: str) -> tuple[int, int, int]:
    ids = len(_ID.findall(selector))
    classes = len(_CLASS.findall(selector)) + len(_ATTRIBUTE.findall(selector)) + len(_PSEUDO.findall(selector))
    remainder = _PSEUDO.sub("", _ATTRIBUTE.sub("", _ID.sub("", _CLASS.sub("", selector))))
    tags = int(bool(remainder.strip()) and remainder.strip() != "*")
    return ids, classes, tags


def _matches(selector: str, element: CssElement) -> bool:
    if "::" in selector or re.search(r"\s|[>+~]", selector):
        return False
    if _ID.search(selector):
        return False
    if any(name not in element.classes for name in _CLASS.findall(selector)):
        return False
    attributes = dict(element.attributes)
    for name, expected in _ATTRIBUTE.findall(selector):
        if name not in attributes or (expected and attributes[name] != expected):
            return False
    for state in _PSEUDO.findall(selector):
        if state == "root":
            if element.tag != "html":
                return False
        elif state not in element.states:
            return False
    remainder = _PSEUDO.sub("", _ATTRIBUTE.sub("", _ID.sub("", _CLASS.sub("", selector)))).strip()
    return remainder in ("", "*", element.tag)


def _normalized_declarations(name: str, value: str) -> list[tuple[str, str]]:
    if name == "background":
        return [("background-color", value)]
    if name == "border":
        colors = re.findall(r"var\(--[a-z0-9-]+\)|#[0-9a-fA-F]{3,8}\b|currentColor", value)
        return [("border-color", colors[-1])] if colors else []
    return [(name, value)]


def _resolve_tokens(value: str, tokens: dict[str, str]) -> str:
    previous = ""
    while value != previous:
        previous = value
        value = _TOKEN.sub(lambda match: tokens.get(match.group(1), match.group(0)), value)
    return value


def computed_style(source: str, element: CssElement, tokens: dict[str, str]) -> dict[str, str]:
    winners: dict[str, _Winner] = {}
    for rule_order, (selectors, declarations) in enumerate(_rules(source)):
        for selector in selectors:
            if not _matches(selector, element):
                continue
            specificity = _specificity(selector)
            for declaration_order, (name, value, important) in enumerate(declarations):
                order = rule_order * 1_000 + declaration_order
                for normalized_name, normalized_value in _normalized_declarations(name, value):
                    candidate = _Winner(normalized_value, important, specificity, order)
                    current = winners.get(normalized_name)
                    if current is None or (candidate.important, candidate.specificity, candidate.order) >= (
                        current.important,
                        current.specificity,
                        current.order,
                    ):
                        winners[normalized_name] = candidate

    parent = computed_style(source, element.parent, tokens) if element.parent else {}
    style = {name: _resolve_tokens(winner.value, tokens) for name, winner in winners.items()}
    if style.get("color") in (None, "inherit"):
        style["color"] = parent.get("color", tokens["--ink"])
    style["parent-background-color"] = parent.get("background-color", tokens["--canvas"])
    if style.get("background-color") in (None, "transparent"):
        style["background-color"] = style["parent-background-color"]
    if style.get("border-color") == "currentColor":
        style["border-color"] = style["color"]
    return style
