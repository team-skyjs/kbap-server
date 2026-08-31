import re
import unittest
from html.parser import HTMLParser
from pathlib import Path


STATIC_ROOT = Path(__file__).resolve().parents[1] / "static"


def css_declarations(source: str, selector: str) -> dict[str, str]:
    match = re.search(rf"{re.escape(selector)}\s*\{{([^}}]*)\}}", source)
    if match is None:
        return {}
    return {
        name.strip(): value.strip()
        for declaration in match.group(1).split(";")
        if ":" in declaration
        for name, value in [declaration.split(":", 1)]
    }


def relative_luminance(color: str) -> float:
    channels = [int(color[index:index + 2], 16) / 255 for index in (1, 3, 5)]
    linear = [channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4 for channel in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def contrast_ratio(first: str, second: str) -> float:
    lighter, darker = sorted((relative_luminance(first), relative_luminance(second)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


class SemanticDocument(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.elements: list[tuple[str, dict[str, str]]] = []
        self.headings: list[tuple[int, str]] = []
        self.text_by_id: dict[str, str] = {}
        self._heading_level: int | None = None
        self._heading_text: list[str] = []
        self._open_ids: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = {name: value or "" for name, value in attrs}
        self.elements.append((tag, attributes))
        self._open_ids.append(attributes.get("id", ""))
        if tag in {"h1", "h2", "h3", "h4", "h5", "h6"}:
            self._heading_level = int(tag[1])
            self._heading_text = []

    def handle_endtag(self, tag: str) -> None:
        if self._heading_level is not None and tag == f"h{self._heading_level}":
            self.headings.append((self._heading_level, "".join(self._heading_text).strip()))
            self._heading_level = None
            self._heading_text = []
        if self._open_ids:
            self._open_ids.pop()

    def handle_data(self, data: str) -> None:
        if self._heading_level is not None:
            self._heading_text.append(data)
        for element_id in self._open_ids:
            if element_id:
                self.text_by_id[element_id] = self.text_by_id.get(element_id, "") + data

    def matching(self, tag: str, **attrs: str) -> list[dict[str, str]]:
        return [
            attributes
            for element_tag, attributes in self.elements
            if element_tag == tag and all(attributes.get(name) == value for name, value in attrs.items())
        ]

    def by_id(self, element_id: str) -> tuple[str, dict[str, str]] | None:
        return next(((tag, attrs) for tag, attrs in self.elements if attrs.get("id") == element_id), None)


class StaticUiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.index_source = (STATIC_ROOT / "index.html").read_text(encoding="utf-8")
        cls.app_source = (STATIC_ROOT / "app.js").read_text(encoding="utf-8")
        cls.styles_source = (STATIC_ROOT / "styles.css").read_text(encoding="utf-8")
        cls.document = SemanticDocument()
        cls.document.feed(cls.index_source)

    def test_landmarks_skip_link_and_heading_hierarchy_are_semantic(self) -> None:
        self.assertTrue(self.document.matching("a", **{"href": "#main-content", "class": "skip-link"}))
        self.assertEqual(1, len(self.document.matching("main", id="main-content")))
        self.assertEqual(1, sum(level == 1 for level, _ in self.document.headings))
        self.assertEqual(len(self.document.headings), len({text for _, text in self.document.headings}))
        levels = [level for level, _ in self.document.headings]
        self.assertTrue(all(current <= previous + 1 for previous, current in zip(levels, levels[1:])))

    def test_catalog_filters_have_explicit_labels(self) -> None:
        for control_id in ("target-search", "suite-filter", "risk-filter"):
            with self.subTest(control_id=control_id):
                self.assertIsNotNone(self.document.by_id(control_id))
                self.assertTrue(self.document.matching("label", **{"for": control_id}))

    def test_run_and_cancel_controls_are_native_buttons(self) -> None:
        for button_id in ("clear-selection-button", "safe-all-button", "selected-run-button", "cancel-button"):
            with self.subTest(button_id=button_id):
                element = self.document.by_id(button_id)
                self.assertIsNotNone(element)
                self.assertEqual("button", element[0] if element else None)

    def test_run_configuration_fields_have_label_help_and_error_regions(self) -> None:
        for control_id in ("profile", "load-value", "extent-value"):
            with self.subTest(control_id=control_id):
                element = self.document.by_id(control_id)
                self.assertIsNotNone(element)
                self.assertTrue(self.document.matching("label", **{"for": control_id}))
                described_by = element[1].get("aria-describedby", "").split() if element else []
                self.assertGreaterEqual(len(described_by), 1)
                self.assertTrue(all(self.document.by_id(region_id) is not None for region_id in described_by))
        self.assertIsNotNone(self.document.by_id("configuration-error"))

    def test_live_status_and_errors_use_announcement_regions(self) -> None:
        live = self.document.by_id("live-status")
        error = self.document.by_id("app-error")
        self.assertEqual("polite", live[1].get("aria-live") if live else None)
        self.assertEqual("alert", error[1].get("role") if error else None)

    def test_results_table_and_artifact_area_are_labelled(self) -> None:
        results = self.document.by_id("results-table")
        self.assertEqual("table", results[0] if results else None)
        self.assertTrue(self.document.matching("caption"))
        self.assertGreaterEqual(len(self.document.matching("th", scope="col")), 5)
        artifacts = self.document.by_id("artifact-downloads")
        self.assertEqual("region", artifacts[1].get("role") if artifacts else None)
        self.assertIn("aria-labelledby", artifacts[1] if artifacts else {})

    def test_assets_are_same_origin_local_and_handlers_are_not_inline(self) -> None:
        resource_paths = [
            attrs.get("href", "")
            for tag, attrs in self.document.elements
            if tag == "link" and attrs.get("rel") == "stylesheet"
        ] + [attrs.get("src", "") for tag, attrs in self.document.elements if tag == "script"]
        self.assertGreaterEqual(len(resource_paths), 2)
        self.assertTrue(all(path.startswith("/") and "://" not in path for path in resource_paths))
        inline_handlers = [
            name
            for _, attrs in self.document.elements
            for name in attrs
            if name.casefold().startswith("on")
        ]
        self.assertEqual([], inline_handlers)

    def test_secret_inputs_browser_storage_and_markup_injection_are_absent(self) -> None:
        forbidden_input_fragments = ("token", "secret", "password", "credential", "authorization")
        for tag, attrs in self.document.elements:
            if tag == "input":
                searchable = " ".join((attrs.get("id", ""), attrs.get("name", ""), attrs.get("type", ""))).casefold()
                self.assertTrue(all(fragment not in searchable for fragment in forbidden_input_fragments))
        self.assertNotIn("localStorage", self.app_source)
        self.assertNotIn("sessionStorage", self.app_source)
        self.assertNotIn("innerHTML", self.app_source)

    def test_korean_section_notes_keep_words_together(self) -> None:
        declarations = css_declarations(self.styles_source, ".section-note")
        self.assertEqual("keep-all", declarations.get("word-break"))

    def test_selected_safe_risk_label_meets_small_text_contrast(self) -> None:
        root = css_declarations(self.styles_source, ":root")
        selected = css_declarations(self.styles_source, ".endpoint-row.is-selected")
        risk = css_declarations(self.styles_source, ".risk-safe")
        background_token = selected["background"].removeprefix("var(").removesuffix(")")
        foreground_token = risk["color"].removeprefix("var(").removesuffix(")")

        self.assertGreaterEqual(contrast_ratio(root[foreground_token], root[background_token]), 4.5)


if __name__ == "__main__":
    unittest.main()
