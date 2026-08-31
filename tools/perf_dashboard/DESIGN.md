# Performance Campaign Dashboard Design System

**Research log**

- Design read: one developer operating high-density load and profiling campaigns without mistakes. `DESIGN_VARIANCE=3`, `MOTION_INTENSITY=2`, `VISUAL_DENSITY=8`.
- Existing admin extraction: `api/src/main/resources/static/assets/admin.css` contributes navy action emphasis (`#0F172A`), sage success/accent (`#059669`), slate copy, light neutral surfaces, semantic success/info/warning/error families, a 4px spacing base, dense tabular numerals, and system-friendly Korean fallbacks. External Google font imports, its three-radius scale, shadows, `min-width: 768px`, and card-heavy composition are rejected for this local tool.
- Operating-dashboard comparison: Sentry contributes dark technical contrast, warm near-black rather than pure black, strict developer-tool hierarchy, and monospace technical data. Its purple/pink/lime multi-accent palette, glass, glow, and branded display font are rejected because they compete with risk and status semantics.
- Operating-dashboard comparison: PostHog contributes dense editorial rhythm, compact dividers, small radii, border/tonal depth, readable long-session typography, and content grouping without generic cards. Its mascot personality, orange hover surprise, warm parchment, proprietary font, and marketing composition are rejected.
- Direction selected: **navy instrument panel with sage control accent**. It combines Sentry's low-glare dark technical contrast with PostHog's compact editorial rows, while preserving the repository's navy, sage, and semantic language. The recognizable moment is the campaign strip: status, phase, elapsed time, cancel control, and artifact readiness remain visually aligned while the main work surface scrolls below.
- Embedded-reference lane: Sentry and PostHog were explicitly prescribed and compared; no additional `_INDEX` shortlist was needed.
- Lazyweb and external design research were skipped because the plan fixes a local research log and forbids external research and calls.
- Imagen and imagegen concept lanes were skipped because the plan explicitly forbids generated imagery and fixes this as a vanilla operational surface.
- UI kit, framework, CDN, icon, and external-font lanes were skipped because the plan requires local HTML, CSS, and JavaScript with no dependency.

## 1. Overview

The dashboard is a localhost-only command surface for selecting endpoint targets, configuring a k6 profile, following a single active campaign, comparing recent results, and downloading allowlisted artifacts. It is not a general analytics dashboard. Every region supports one operating decision: what will run, how risky it is, what is running now, whether it passed, and what evidence is ready.

The desktop shell is bounded to `100dvb`: a fixed header sits above a `list-detail` body. The catalog is the stable command rail while `#detail-scroll` is the named main vertical scroll owner for configuration, live state, history, and artifacts. Because the endpoint catalog can contain dozens of rows, `#target-list` is the explicitly named nested scroll region whose only job is scrolling endpoint choices beneath the fixed catalog heading and filters. Grid and flex descendants use `min-block-size: 0` and `min-inline-size: 0`; identifiers and routes use `overflow-wrap: anywhere`.

At `768px` and below the shell becomes a normal single-column document: catalog, configuration, live campaign, and results appear in operating order and the page owns vertical scrolling. At `375px`, primary content never requires horizontal scrolling. Result tables recompose into labelled rows rather than creating a two-dimensional primary scroll region.

## 2. Principles

1. **Prevent expensive mistakes.** Safe targets are selected by default. Fixture and cost targets require an adjacent warning, approval scoped to the exact risky selection and load configuration, and separate target-iteration, maximum HTTP request, and downstream billable-call estimates before execution.
2. **State is text first.** Color supports, never replaces, `QUEUED`, `RUNNING`, `PASSED`, `FAILED`, `CANCELLED`, threshold labels, reconnect messages, and partial-collection explanations.
3. **Dense, not cramped.** Repeated rows use dividers and alignment rather than card containers. Spacing separates tasks, not every datum.
4. **One source of truth.** The browser consumes only the actual same-origin Task 9 JSON, SSE, bundle, and artifact routes. It never invents storage, token, or remote artifact APIs.
5. **Calm motion.** Motion communicates press, focus, loading, or state replacement only. No decorative loops. Reduced-motion removes all non-essential transitions.
6. **Recoverable operation.** Initial fetch, restore, SSE reconnect, snapshot fallback, cancel, failed targets, and partial artifacts all retain context and a next action.

## 3. Brand

Atmosphere: a calm navy instrument panel with editorial density and sage control emphasis. It should feel closer to a reliable build console than a consumer dashboard. The surface is serious without looking punitive; Korean labels and technical identifiers have equal visual authority.

The accent is sage and appears only on primary action, active selection, links, and focus. Navy supplies hierarchy and primary ink, not a second accent. Success, warning, error, and info colors are semantic tokens only and must always accompany explicit text.

No decorative dots, emoji, hand-drawn icons, gradient mesh, glass, glow, section theme flip, hero composition, or generic metric-card grid. No em dash or en dash in visible copy. Local system fonts are part of the brand: dependable Korean rendering matters more than a distinctive downloaded face.

## 4. Foundations

### Color tokens

Only the values in this table may appear as raw colors in product CSS. Light is the default; dark is selected once at the document level by `prefers-color-scheme`.

| Role | Token | Light | Dark | Use |
|---|---|---|---|---|
| Canvas | `--canvas` | `#F6F7F9` | `#0B101B` | App background |
| Surface | `--surface` | `#FFFFFF` | `#111827` | Primary working surface |
| Surface muted | `--surface-muted` | `#F1F5F9` | `#182235` | Rows, controls, secondary bands |
| Surface strong | `--surface-strong` | `#E2E8F0` | `#223047` | Selected and pressed tonal step |
| Ink | `--ink` | `#0F172A` | `#F8FAFC` | Primary copy |
| Ink muted | `--ink-muted` | `#475569` | `#CBD5E1` | Secondary copy |
| Ink faint | `--ink-faint` | `#64748B` | `#94A3B8` | Metadata and disabled copy |
| Border | `--border` | `#CBD5E1` | `#334155` | All containment and dividers |
| Control border | `--control-border` | `#64748B` | `#94A3B8` | Input and select component boundary |
| Navy | `--navy` | `#0F172A` | `#E2E8F0` | Strong neutral action and headings |
| Navy pressed | `--navy-pressed` | `#020617` | `#F8FAFC` | Primary action hover/press |
| Accent | `--accent` | `#047857` | `#6EE7B7` | Only interactive accent |
| Accent hover | `--accent-hover` | `#065F46` | `#A7F3D0` | Accent action hover state |
| Accent surface | `--accent-surface` | `#D1FAE5` | `#064E3B` | Selected and focus-adjacent fill |
| Success | `--success` | `#166534` | `#86EFAC` | Passed and safe text |
| Success surface | `--success-surface` | `#DCFCE7` | `#14532D` | Passed background |
| Warning | `--warning` | `#92400E` | `#FDE68A` | Risk and partial text |
| Warning surface | `--warning-surface` | `#FEF3C7` | `#451A03` | Risk and partial background |
| Error | `--error` | `#B91C1C` | `#FCA5A5` | Failure and destructive text |
| Error surface | `--error-surface` | `#FEE2E2` | `#450A0A` | Failure background |
| Info | `--info` | `#1D4ED8` | `#93C5FD` | Reconnect and running text |
| Info surface | `--info-surface` | `#DBEAFE` | `#172554` | Reconnect and running background |
| Focus outline | `--focus` | `#047857` | `#6EE7B7` | Keyboard focus only |

### Typography

- UI: `system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif`.
- Technical: `ui-monospace, "SFMono-Regular", Menlo, Monaco, Consolas, "Liberation Mono", monospace`.
- H1 `1.5rem/1.25`, 750; H2 `1.125rem/1.35`, 700; H3 `1rem/1.4`, 700; body `0.875rem/1.5`, 400; label `0.8125rem/1.4`, 650; caption `0.75rem/1.45`, 550.
- Body text never drops below 12px. Routes, campaign IDs, durations, timestamps, and metrics use the technical stack with tabular numerals.

### Spacing, radius, and depth

- Base unit is 4px: `--space-1: 0.25rem`, `--space-2: 0.5rem`, `--space-3: 0.75rem`, `--space-4: 1rem`, `--space-5: 1.25rem`, `--space-6: 1.5rem`, `--space-8: 2rem`.
- One radius system: `--radius: 0.375rem` for every control, field, surface, badge, and callout. Pills are not used.
- Depth strategy: borders plus tonal shifts. Product surfaces use no box shadow. Focus uses a 3px outline and offset, not glow.
- Touch target: `--control-size: 2.75rem` where practical. Dense check rows may have a visually compact body but their label hit area remains at least 44px high.

### Layout

- Header row: content height, fixed inside the desktop `100dvb` shell.
- Body: `minmax(0, 1fr)`; list-detail columns `minmax(17rem, 22rem) minmax(0, 1fr)`.
- Catalog command controls stay fixed in the desktop rail. `#target-list` owns the bounded endpoint-list scrollbar; `#detail-scroll` independently owns the main results scrollbar. These are the only two desktop vertical scroll regions and each has one explicit job.
- Content maximum: `100rem`; gutters scale from `--space-3` at narrow widths to `--space-6` at desktop.
- At `≤768px`, grid columns become one, the height bound is removed, and source order is preserved.

## 5. Components

### Button

- Variants: primary navy, secondary surface, accent safe-run, destructive cancel, link-style download.
- States: default, hover, focus-visible, active, disabled, loading, error-adjacent.
- Labels never wrap at desktop. Loading keeps the label and adds text such as `실행 요청 중` without an icon dependency.
- Disabled controls preserve readable text and their 3:1 component boundary without whole-control opacity; use `aria-disabled` only for links that cannot use the native `disabled` attribute.

### Form control

- Text search, select, number/text input, checkbox, and profile selector share border, radius, min height, label, help region, and inline error region.
- Profile selection changes load and extent labels and help copy without moving focus. Invalid values set `aria-invalid=true` and point to the same contextual error region.

### Endpoint row

- Structure: checkbox label, method badge, endpoint label, route, suite, and risk text in one divider-separated row.
- States: selected, hover, focus-within, disabled while active, fixture warning, cost warning, empty filter result.
- Method badges are neutral, monospaced text. Risk never relies on a dot or color alone.
- Disabled rows carry `aria-disabled=true`, a not-allowed cursor, and no hover/active fill; selected rows retain the checkbox plus an accent inline marker without layout shift.

### Status and risk callout

- Status variants: queued, running, cancelling, passed, failed, cancelled, reconnecting, partial.
- Risk callout sits immediately before execution controls and includes selected risky targets, scoped approval state, total target iterations, maximum HTTP requests, maximum downstream billable calls, and the fixture-exhaustion caveat.
- Status text uses `aria-live=polite`; blocking/API validation errors use `role=alert`.

### Metric and result row

- Metrics align p95, p99, failure rate, dropped iterations, threshold, absolute delta, and percent delta on tabular numerals.
- Result rows are dividers in a semantic table on wide screens and labelled stacked cells at 768px and below. Missing comparison reads `비교 기준 없음`.

### Artifact link

- Variants: ready link, inline HTML report, download, unavailable disabled text, partial-collection explanation, campaign ZIP.
- Every ready link uses only `/api/runs/{campaignId}/artifacts/{artifactId}` or `/api/runs/{campaignId}/bundle`.

### Console line

- Created with DOM text nodes and `textContent` only. Monospace, natural wrapping, no markup interpretation, maximum retained line count documented in code.

## 6. Patterns

### Initial and restored state

Fetch `/api/targets` and `/api/runs` in parallel. While loading, keep labelled skeleton rows. On success, select targets where `defaultEnabled` and `risk=safe`; if history contains an active campaign, restore it, disable mutable controls, fetch its snapshot, and reconnect SSE.

### Selection and execution

Search, suite, and risk filters only affect visibility, never silently alter selection. `안전 대상 선택` restores the safe defaults compatible with the current profile, while `선택 해제` explicitly clears every selected target including targets hidden by filters. `선택 실행` posts `mode=selected`, target keys, profile, load, extent, risk approval, and JFR state to `/api/runs`. A single-target smoke may disable JFR; every other mode keeps JFR enabled per server validation.

Fixture or cost selection exposes explicit approval. Approval binds the sorted risky target keys plus profile, load, and extent; changing any of those inputs or reaching terminal state clears it. A new risky target can never reuse an earlier acknowledgement.

The estimator mirrors `run-endpoint.sh` phases. Smoke performs `3` target iterations per target: smoke, warmup, measurement. Read and write perform `1 + rate × 120 warmup seconds + rate × requested measurement seconds`. External performs `1 smoke + 1 warmup + VU × iterations`. Target iterations are shown separately from maximum HTTP requests. Each target carries reviewed `requestsPerIteration` manifest metadata; current ordinary targets use `1`, while each scan-v2 iteration uses `2` for ticket plus scan. Maximum downstream billable calls count cost targets only. Fixture exhaustion can reduce actual write requests below the upper bound.

### Active campaign and SSE

On `202`, render the returned campaign immediately, move focus to the active heading without scrolling the shell, and connect `/api/runs/{id}/events`. Track increasing SSE `lastEventId`, phase, status, target, and sanitized line per campaign ID. Exact event schema is `target`, `phase`, `status`, `line`; extra fields and wrong types trigger snapshot recovery without consuming sequence. Same-campaign reconnect preserves stream state and reuses an in-flight recovery, while campaign switches reset sequence, phases, console, and stale snapshots. Snapshot requests use increasing client generations plus abort signals; only the latest matching generation may apply, and a terminal current campaign always dominates a late active snapshot. On error, close the source, announce reconnect, fetch `/api/runs/{id}`, and retry with exponential delay `1s, 2s, 4s, 8s, 10s` maximum. Terminal snapshots stop reconnect and clear the live panel while remaining selectable in Results.

Cancel posts once to `/api/runs/{id}/cancel`; the button disables immediately and stays disabled through `CANCELLING` and terminal state. `409`, `400`, network, failed, cancelled, reconnect, and partial states use contextual Korean explanations.

### History and comparison

Recent campaigns are selectable by real campaign ID. For each target with a summary, compare against the nearest older campaign containing the same target. Show current value, absolute difference, and percentage difference; a zero or missing baseline yields `비교 기준 없음`. Threshold `통과`, `실패`, and `데이터 없음` remain explicit.

### Artifact collection

Match artifact names from the campaign response, not guessed IDs. JFR-enabled campaigns expect `report.html`, `summary.json`, `manifest.json`, and two `.jfr` entries per completed target. A supported single-target smoke with `jfrEnabled=false` expects only the three non-JFR files and says `JFR 수집 안 함`; missing JFR files are not partial failure in that intentional state. Until required files are present, show `수집 중` or `부분 수집` and never create a dead anchor. ZIP is offered for a terminal campaign and points to the same-origin bundle route.

## 7. Content

- Voice: direct Korean operating language. Use verbs: `선택`, `실행`, `취소`, `다시 연결`, `다운로드`.
- Profiles always explain meaning: smoke is three runner phases at one iteration each, read and write are constant-arrival-rate request load, and external is bounded VU × iteration paid/external load.
- Never display access tokens, authorization fields, fixture secrets, S3 URLs, or raw environment data. The console says it is server-sanitized but does not imply absolute secrecy.
- Dates use local readable time plus the original campaign ID. Percentages and milliseconds declare units.
- Avoid promotional language, cute metaphors, decorative punctuation, emoji, em dash, and en dash.
- Empty state says why it is empty and what action changes it. Error state preserves current selection and identifies whether retry is safe.
- JFR copy warns that recordings can contain sensitive internal class, method, thread, and environment context.

## 8. Accessibility

Primary persona: a single Korean-speaking backend developer switching between terminal, browser, Grafana, and JMC under time pressure. Situational constraints include one-hand trackpad use, 200% zoom, long CJK labels, long unbroken IDs, temporary network loss, and reduced attention during an active incident.

- Target WCAG 2.2 AA: body contrast at least 4.5:1, large text and component boundaries at least 3:1.
- A skip link precedes the header and targets `main`. One H1 names the application; H2 regions follow source order; target details use H3 only inside their H2 region.
- Every input has an explicit `label`; table has `caption`, row and column headers; live status uses `aria-live=polite`; API/validation failure uses `role=alert`.
- Keyboard order follows visual/source order. All actions work with keyboard, focus is never hidden by a fixed region, and `:focus-visible` is unmistakable in light and dark themes.
- Touch targets are 44px where practical. Checkbox rows make the entire label selectable. Destructive cancel is separated from execution controls and requires no precision icon target.
- At 200% zoom, content reflows without loss. CJK prose uses `word-break:keep-all` with normal wrapping; dedicated technical elements use `overflow-wrap:anywhere`. Primary content does not scroll horizontally at 375px.
- `prefers-reduced-motion: reduce` sets transition duration to zero and removes loading animation. `prefers-color-scheme` applies once at the root without section-level flips.
- Accepted debt: automated browser checks cannot prove screen-reader announcement quality; the runbook retains a manual VoiceOver/NVDA check as an exit criterion. Owner: dashboard maintainer, exit: first shared use beyond the author.
- Accepted debt: native `EventSource` retry timing is not controllable, so the client closes it and schedules a new source explicitly. Owner: dashboard maintainer, exit: none while using native SSE.
