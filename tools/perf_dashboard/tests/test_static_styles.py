import re
import unittest
from pathlib import Path

from tools.perf_dashboard.tests.css_cascade import CssElement, computed_style


STATIC_ROOT = Path(__file__).resolve().parents[1] / "static"


def css_declarations(source: str, selector: str) -> dict[str, str]:
    expected = re.sub(r"\s+", " ", selector.strip())
    result: dict[str, str] = {}
    for rule_selector, body in re.findall(r"([^{}]+)\{([^{}]*)\}", source):
        if re.sub(r"\s+", " ", rule_selector.strip()) != expected:
            continue
        result.update({
            name.strip(): value.strip()
            for declaration in body.split(";")
            if ":" in declaration
            for name, value in [declaration.split(":", 1)]
        })
    return result


def css_blocks(source: str, header: str) -> list[str]:
    blocks: list[str] = []
    cursor = 0
    while True:
        start = source.find(header, cursor)
        if start < 0:
            return blocks
        opening = source.find("{", start + len(header))
        depth = 0
        for index in range(opening, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    blocks.append(source[opening + 1:index])
                    cursor = index + 1
                    break
        else:
            raise AssertionError(f"unclosed CSS block: {header}")


def relative_luminance(color: str) -> float:
    channels = [int(color[index:index + 2], 16) / 255 for index in (1, 3, 5)]
    linear = [channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4 for channel in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def contrast_ratio(first: str, second: str) -> float:
    lighter, darker = sorted((relative_luminance(first), relative_luminance(second)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


def token_name(value: str) -> str:
    match = re.search(r"var\((--[a-z0-9-]+)\)", value)
    return match.group(1) if match else ""


class StaticStyleContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app_source = (STATIC_ROOT / "app.js").read_text(encoding="utf-8")
        cls.showcase_source = (STATIC_ROOT / "showcase.html").read_text(encoding="utf-8")
        cls.styles_source = (STATIC_ROOT / "styles.css").read_text(encoding="utf-8")
        dark_start = cls.styles_source.index("@media (prefers-color-scheme: dark)")
        cls.light = css_declarations(cls.styles_source[:dark_start], ":root")
        dark_source = cls.styles_source[dark_start:]
        cls.dark = css_declarations(dark_source, ":root")

    def assert_token_contrast(self, foreground: str, background: str, minimum: float) -> None:
        for root in (self.light, self.dark):
            with self.subTest(theme=root["--surface"], foreground=foreground, background=background):
                self.assertGreaterEqual(contrast_ratio(root[foreground], root[background]), minimum)

    def assert_action_contrast(self, source: str, element: CssElement) -> dict[str, str]:
        for tokens in (self.light, self.dark):
            style = computed_style(source, element, tokens)
            self.assertGreaterEqual(contrast_ratio(style["color"], style["background-color"]), 4.5)
            self.assertGreaterEqual(contrast_ratio(style["border-color"], style["parent-background-color"]), 3.0)
        return computed_style(source, element, self.light)

    def assert_danger_semantics(self, source: str, element: CssElement) -> None:
        for tokens in (self.light, self.dark):
            style = computed_style(source, element, tokens)
            if "disabled" in element.states:
                self.assertEqual(tokens["--ink-muted"], style["color"])
                self.assertEqual(tokens["--control-border"], style["border-color"])
                self.assertEqual(tokens["--surface-muted"], style["background-color"])
            else:
                self.assertEqual(tokens["--error"], style["color"])
                self.assertEqual(tokens["--error"], style["border-color"])

    def test_prose_wrap_is_normal_and_anywhere_is_technical_only(self) -> None:
        body = css_declarations(self.styles_source, "body")
        self.assertEqual(("keep-all", "normal"), (body.get("word-break"), body.get("overflow-wrap")))
        allowed = {
            "code",
            ".artifact-target-key",
            ".console-output",
            ".phase-label",
            ".risk-target-key",
            ".endpoint-route, .campaign-id, .active-target-row code",
        }
        actual = {
            re.sub(r"\s+", " ", selector.strip())
            for selector, declarations in re.findall(r"([^{}]+)\{([^{}]*)\}", self.styles_source)
            if re.search(r"overflow-wrap\s*:\s*anywhere", declarations)
        }
        self.assertEqual(allowed, actual)
        self.assertIn('element("code", "risk-target-key", target.key)', self.app_source)
        self.assertIn('element("h4", "artifact-target-key", target.key)', self.app_source)

    def test_selected_text_and_marker_contrasts_are_explicit(self) -> None:
        selected = css_declarations(self.styles_source, ".endpoint-row.is-selected")
        risk = css_declarations(self.styles_source, ".risk-safe")
        background = token_name(selected["background"])
        self.assert_token_contrast(token_name(risk["color"]), background, 4.5)
        self.assert_token_contrast(token_name(selected["border-inline-start-color"]), background, 3.0)

    def test_exact_768_breakpoint_owns_reflow_rules(self) -> None:
        mobile_blocks = css_blocks(self.styles_source, "@media (max-width: 48rem)")
        product = next((block for block in mobile_blocks if ".list-detail" in block), "")
        shell = css_declarations(product, ".app-shell")
        workspace = css_declarations(product, ".workspace")
        list_detail = css_declarations(product, ".list-detail")
        results = css_declarations(product, ".results-wrap")
        table = css_declarations(product, "table")
        rows = css_declarations(product, "td,\n  tbody th")
        self.assertEqual(("auto", "visible"), (shell.get("block-size"), shell.get("overflow")))
        self.assertEqual("visible", workspace.get("overflow"))
        self.assertEqual(("1fr", "auto"), (list_detail.get("grid-template-columns"), list_detail.get("block-size")))
        self.assertEqual(("visible", "0", "grid"), (results.get("overflow-x"), table.get("min-inline-size"), rows.get("display")))
        self.assertNotIn("@media (max-width: 47.999rem)", self.styles_source)

    def test_form_boundaries_include_disabled_state(self) -> None:
        controls = css_declarations(self.styles_source, ".field input,\n.field select")
        self.assert_token_contrast(token_name(controls["border"]), token_name(controls["background"]), 3.0)
        disabled = css_declarations(self.styles_source, ".field input:disabled,\n.field select:disabled")
        self.assertEqual(("1", "--control-border"), (disabled.get("opacity"), token_name(disabled["border-color"])))
        self.assert_token_contrast(token_name(disabled["border-color"]), token_name(disabled["background"]), 3.0)

    def test_button_cascade_preserves_boundaries_and_text_contrast(self) -> None:
        parent = CssElement("div", frozenset({"state-sample"}), parent=CssElement("html"))
        variants = (frozenset(), frozenset({"button-primary"}), frozenset({"button-accent"}), frozenset({"button-danger"}))
        states = (frozenset(), frozenset({"hover"}), frozenset({"active"}), frozenset({"hover", "active"}), frozenset({"disabled"}))
        for classes in variants:
            for state in states:
                with self.subTest(classes=classes, state=state):
                    style = self.assert_action_contrast(self.styles_source, CssElement("button", classes, states=state, parent=parent))
                    expected_background = "--surface"
                    if "disabled" in state:
                        expected_background = "--surface-muted"
                    elif "button-primary" in classes:
                        expected_background = "--navy-pressed" if "hover" in state else "--navy"
                    elif "button-accent" in classes:
                        expected_background = "--accent-hover" if "hover" in state else "--accent"
                    elif "hover" in state:
                        expected_background = "--surface-muted"
                    self.assertEqual(self.light[expected_background], style["background-color"])
                    if "button-danger" in classes:
                        self.assert_danger_semantics(self.styles_source, CssElement("button", classes, states=state, parent=parent))
                    if "active" in state:
                        self.assertEqual("translateY(1px)", style.get("transform"))

        for state in states[:-1]:
            with self.subTest(link_state=state):
                link = CssElement("a", frozenset({"button", "download-link"}), states=state, parent=parent)
                style = self.assert_action_contrast(self.styles_source, link)
                expected_background = "--accent-surface" if "hover" in state else "--surface"
                self.assertEqual(self.light[expected_background], style["background-color"])
                if "active" in state:
                    self.assertEqual("translateY(1px)", style.get("transform"))

        for state in states[:-1]:
            with self.subTest(download_state=state):
                download = CssElement("a", frozenset({"download-link"}), states=state, parent=parent)
                for tokens in (self.light, self.dark):
                    style = computed_style(self.styles_source, download, tokens)
                    expected_background = "--accent-surface" if "hover" in state else "--surface"
                    self.assertEqual(tokens[expected_background], style["background-color"])
                    self.assertGreaterEqual(contrast_ratio(style["color"], style["background-color"]), 4.5)

    def test_cascade_mutations_are_observable(self) -> None:
        parent = CssElement("div", frozenset({"state-sample"}), parent=CssElement("html"))
        accent = CssElement("button", frozenset({"button-accent"}), states=frozenset({"hover"}), parent=parent)
        later_bad = self.styles_source + "\n.button-accent:hover { color: var(--accent-hover); }\n"
        self.assertEqual(self.light["--accent-hover"], computed_style(later_bad, accent, self.light)["color"])
        with self.assertRaises(AssertionError):
            self.assert_action_contrast(later_bad, accent)

        danger = CssElement("button", frozenset({"button-danger"}), states=frozenset({"hover"}), parent=parent)
        specific_bad = self.styles_source + "\nbutton.button-danger:hover { border-color: var(--surface); }\n"
        self.assertEqual(self.light["--surface"], computed_style(specific_bad, danger, self.light)["border-color"])
        with self.assertRaises(AssertionError):
            self.assert_action_contrast(specific_bad, danger)

        neutral_danger = self.styles_source + "\nbutton.button-danger { color: var(--ink); border-color: var(--control-border); }\n"
        self.assert_action_contrast(neutral_danger, CssElement("button", frozenset({"button-danger"}), parent=parent))
        with self.assertRaises(AssertionError):
            self.assert_danger_semantics(neutral_danger, CssElement("button", frozenset({"button-danger"}), parent=parent))

        disabled_danger = CssElement("button", frozenset({"button-danger"}), states=frozenset({"disabled"}), parent=parent)
        saturated_disabled = self.styles_source + "\nbutton.button-danger:disabled { color: var(--error); border-color: var(--error); }\n"
        self.assert_action_contrast(saturated_disabled, disabled_danger)
        with self.assertRaises(AssertionError):
            self.assert_danger_semantics(saturated_disabled, disabled_danger)

    def test_disabled_endpoint_row_suppresses_pointer_affordances(self) -> None:
        disabled = css_declarations(self.styles_source, ".endpoint-row.is-disabled")
        hover = css_declarations(
            self.styles_source,
            ".endpoint-row.is-disabled:hover,\n.endpoint-row.is-disabled:focus-within,\n.endpoint-row.is-disabled:active,\n.endpoint-row.is-disabled.demo-focus-within",
        )
        selected = css_declarations(self.styles_source, ".endpoint-row.is-disabled.is-selected")
        selected_interaction = css_declarations(
            self.styles_source,
            ".endpoint-row.is-disabled.is-selected:hover,\n.endpoint-row.is-disabled.is-selected:focus-within,\n.endpoint-row.is-disabled.is-selected:active,\n.endpoint-row.is-disabled.is-selected.demo-focus-within",
        )

        self.assertEqual("not-allowed", disabled.get("cursor"))
        self.assertEqual("var(--surface)", hover.get("background"))
        self.assertEqual("var(--accent-surface)", selected.get("background"))
        self.assertEqual("var(--accent-surface)", selected_interaction.get("background"))
        self.assertIn('label.classList.add("is-disabled")', self.app_source)
        self.assertIn('label.setAttribute("aria-disabled", "true")', self.app_source)

    def test_reduced_motion_removes_transition_and_loading_animation(self) -> None:
        reduced = css_blocks(self.styles_source, "@media (prefers-reduced-motion: reduce)")[0]
        universal = css_declarations(reduced, "*,\n  *::before,\n  *::after")
        loading = css_declarations(reduced, ".button-loading::before,\n  .loading-line::before")

        self.assertEqual("0s !important", universal.get("transition-duration"))
        self.assertEqual("none", loading.get("animation"))

    def test_focus_touch_and_375_contracts_are_explicit(self) -> None:
        focus = css_declarations(self.styles_source, ":focus-visible,\n.demo-focus")
        button = css_declarations(self.styles_source, "button,\n.button")
        field = css_declarations(self.styles_source, ".field input,\n.field select")
        header_link = css_declarations(self.styles_source, ".header-link")
        download = css_declarations(self.styles_source, ".download-link")
        narrow = css_blocks(self.styles_source, "@media (max-width: 23.5rem)")[0]
        endpoint = css_declarations(narrow, ".endpoint-row")
        risk = css_declarations(narrow, ".endpoint-row > .risk-label,\n  .endpoint-row > .risk-safe")
        catalog_actions = css_declarations(narrow, ".catalog-actions")
        catalog_button = css_declarations(narrow, ".catalog-actions > button")

        self.assertEqual(("3px solid var(--focus)", "2px"), (focus.get("outline"), focus.get("outline-offset")))
        self.assertEqual("var(--control-size)", button.get("min-block-size"))
        self.assertEqual("var(--control-size)", field.get("min-block-size"))
        self.assertEqual("2.75rem", self.light.get("--control-size"))
        self.assertEqual("auto minmax(0, 1fr)", endpoint.get("grid-template-columns"))
        self.assertEqual("2", risk.get("grid-column"))
        self.assertEqual("1fr", catalog_actions.get("grid-template-columns"))
        self.assertEqual(("0", "100%"), (catalog_button.get("min-inline-size"), catalog_button.get("inline-size")))
        self.assertEqual("nowrap", button.get("white-space"))
        self.assertEqual(("inline-flex", "center", "var(--control-size)"), (header_link.get("display"), header_link.get("align-items"), header_link.get("min-block-size")))
        self.assertEqual("var(--control-size)", download.get("min-block-size"))

    def test_selector_helper_uses_final_exact_rule(self) -> None:
        fixture = ".sample { color: first; } .other .sample { color: wrong; } .sample { color: final; background: set; }"

        self.assertEqual({"color": "final", "background": "set"}, css_declarations(fixture, ".sample"))


if __name__ == "__main__":
    unittest.main()
