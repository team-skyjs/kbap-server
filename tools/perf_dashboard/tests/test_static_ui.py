import unittest
from html.parser import HTMLParser
from pathlib import Path


STATIC_ROOT = Path(__file__).resolve().parents[1] / "static"


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
        cls.showcase_source = (STATIC_ROOT / "showcase.html").read_text(encoding="utf-8")
        cls.styles_source = (STATIC_ROOT / "styles.css").read_text(encoding="utf-8")
        cls.document = SemanticDocument()
        cls.document.feed(cls.index_source)
        cls.showcase = SemanticDocument()
        cls.showcase.feed(cls.showcase_source)

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

    def test_sse_payload_is_parsed_before_snapshot_invalidation(self) -> None:
        apply_event = self.app_source[
            self.app_source.index("function applyEvent(event)"):
            self.app_source.index("function closeEvents()")
        ]

        self.assertLess(apply_event.index("parseSsePayload(event.data)"), apply_event.index("snapshots.acceptEvent"))
        invalid_branch = apply_event[apply_event.index("if (!parsed.valid)"):apply_event.index("snapshots.acceptEvent")]
        self.assertIn("refreshSnapshot", invalid_branch)
        self.assertIn(", true", invalid_branch)

    def test_sse_listener_is_scoped_to_its_campaign(self) -> None:
        connect = self.app_source[
            self.app_source.index("function connectEvents(campaignId)"):
            self.app_source.index("async function startCampaign")
        ]

        self.assertLess(connect.index("scopeStream(campaignId)"), connect.index("new EventSource"))
        self.assertIn("state.streamCampaignId !== campaignId", connect)
        scope = self.app_source[self.app_source.index("function scopeStream(campaignId)"):self.app_source.index("function bindControls()")]
        self.assertIn("snapshots.abort()", scope)

    def test_results_table_and_artifact_area_are_labelled(self) -> None:
        results = self.document.by_id("results-table")
        self.assertEqual("table", results[0] if results else None)
        self.assertTrue(self.document.matching("caption"))
        self.assertGreaterEqual(len(self.document.matching("th", scope="col")), 5)
        artifacts = self.document.by_id("artifact-downloads")
        self.assertEqual("region", artifacts[1].get("role") if artifacts else None)
        self.assertIn("aria-labelledby", artifacts[1] if artifacts else {})
        self.assertEqual(1, len(self.showcase.matching("div", role="table")))

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
                if attrs.get("id") == "jwt-secret":
                    self.assertEqual("password", attrs.get("type"))
                    self.assertEqual("off", attrs.get("autocomplete"))
                    continue
                searchable = " ".join((attrs.get("id", ""), attrs.get("name", ""), attrs.get("type", ""))).casefold()
                self.assertTrue(all(fragment not in searchable for fragment in forbidden_input_fragments))
        self.assertNotIn("localStorage", self.app_source)
        self.assertNotIn("sessionStorage", self.app_source)
        self.assertNotIn("innerHTML", self.app_source)

    def test_showcase_covers_documented_status_matrix(self) -> None:
        statuses = {
            attrs.get("data-status")
            for _, attrs in self.showcase.elements
            if attrs.get("data-status")
        }

        self.assertEqual(
            {"QUEUED", "RUNNING", "CANCELLING", "PASSED", "FAILED", "CANCELLED", "RECONNECTING", "PARTIAL"},
            statuses,
        )

    def test_showcase_covers_representative_form_and_result_states(self) -> None:
        normal_select = self.showcase.by_id("show-profile")
        focused_select = self.showcase.by_id("show-suite")
        disabled_select = self.showcase.by_id("show-profile-disabled")
        normal_input = self.showcase.by_id("show-rate")
        invalid_input = self.showcase.by_id("show-iterations")
        disabled_input = self.showcase.by_id("show-rate-disabled")
        result_states = {
            attrs.get("data-result-state")
            for _, attrs in self.showcase.elements
            if attrs.get("data-result-state")
        }

        self.assertEqual("select", normal_select[0] if normal_select else None)
        self.assertIn("demo-focus", focused_select[1].get("class", "") if focused_select else "")
        self.assertIn("disabled", disabled_select[1] if disabled_select else {})
        self.assertEqual("input", normal_input[0] if normal_input else None)
        self.assertEqual("true", invalid_input[1].get("aria-invalid") if invalid_input else None)
        self.assertIn("disabled", disabled_input[1] if disabled_input else {})
        self.assertEqual({"PASSED", "PARTIAL"}, result_states)
        self.assertGreaterEqual(len(self.showcase.matching("div", role="rowheader")), 2)

    def test_showcase_covers_active_button_and_endpoint_states(self) -> None:
        button_states = {attrs.get("data-button-state") for _, attrs in self.showcase.elements if attrs.get("data-button-state")}
        endpoint_states = {attrs.get("data-endpoint-state") for _, attrs in self.showcase.elements if attrs.get("data-endpoint-state")}
        disabled_row = next((attrs for _, attrs in self.showcase.elements if attrs.get("data-endpoint-state") == "disabled"), {})
        focus_row = next((attrs for _, attrs in self.showcase.elements if attrs.get("data-endpoint-state") == "focus"), {})
        focus_input = self.showcase.by_id("show-endpoint-focus")
        hover_samples = [attrs for _, attrs in self.showcase.elements if attrs.get("data-demo-hover")]
        hover_kinds = {attrs.get("data-demo-hover") for attrs in hover_samples}

        self.assertIn("active", button_states)
        self.assertEqual({"selected", "hover", "focus", "disabled", "fixture", "cost", "empty"}, endpoint_states)
        self.assertIn("is-disabled", disabled_row.get("class", ""))
        self.assertEqual("true", disabled_row.get("aria-disabled"))
        self.assertIn("demo-focus-within", focus_row.get("class", ""))
        self.assertIn("demo-focus", focus_input[1].get("class", "") if focus_input else "")
        self.assertEqual({"button", "checkbox", "endpoint", "download"}, hover_kinds)
        self.assertTrue(all("demo-hover" in attrs.get("class", "").split() for attrs in hover_samples))

    def test_header_showcase_link_is_native_and_named(self) -> None:
        header_showcase = self.document.by_id("header-showcase")

        self.assertEqual("a", header_showcase[0] if header_showcase else None)
        self.assertEqual("/showcase.html", header_showcase[1].get("href") if header_showcase else None)


if __name__ == "__main__":
    unittest.main()
