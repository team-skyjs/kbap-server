import assert from "node:assert/strict";
import test from "node:test";

let model = {};
try {
  model = await import(new URL("../static/model.mjs", import.meta.url));
} catch (error) {
  if (error?.code !== "ERR_MODULE_NOT_FOUND") throw error;
}

function invoke(name, ...args) {
  assert.equal(typeof model[name], "function", `${name} must be exported`);
  return model[name](...args);
}

function endpoint(key, risk = "safe", requestsPerIteration = 1) {
  return { key, risk, requestsPerIteration };
}

function deferred() {
  let resolve;
  const promise = new Promise((accept) => { resolve = accept; });
  return { promise, resolve };
}

test("read and write use request rate while external uses virtual users", () => {
  // Given profile names used by the real runner.
  // When their semantics are requested.
  const read = invoke("profileRule", "read");
  const write = invoke("profileRule", "write");
  const external = invoke("profileRule", "external");

  // Then rate profiles and VU profiles stay distinct.
  assert.equal(read.loadKind, "rate");
  assert.equal(read.loadLabel, "초당 요청률");
  assert.equal(write.loadKind, "rate");
  assert.equal(write.loadLabel, "초당 요청률");
  assert.equal(external.loadKind, "vus");
  assert.equal(external.loadLabel, "가상 사용자 수");
});

test("smoke budget includes smoke warmup and measurement phases", () => {
  // Given one risky ordinary endpoint.
  const targets = [endpoint("fixture-a", "fixture")];

  // When a smoke budget is estimated.
  const budget = invoke("estimateRunBudget", { targets, profile: "smoke", load: 1, extent: "1" });

  // Then all three target iterations and HTTP requests are counted.
  assert.deepEqual(budget, {
    valid: true,
    targetIterations: 3,
    maximumHttpRequests: 3,
    maximumBillableRequests: 0,
    fixtureTargetIterations: 3,
  });
});

test("write budget includes one smoke and 120 seconds of warmup", () => {
  // Given the maximum dashboard write profile.
  const targets = [endpoint("fixture-a", "fixture")];

  // When its budget is estimated.
  const budget = invoke("estimateRunBudget", { targets, profile: "write", load: 10, extent: "120s" });

  // Then the scheduled target iterations include both rate phases.
  assert.equal(budget.targetIterations, 2401);
  assert.equal(budget.maximumHttpRequests, 2401);
  assert.equal(budget.fixtureTargetIterations, 2401);
});

test("external 10 by 10 budget includes smoke and one warmup iteration", () => {
  // Given one cost endpoint at the dashboard cap.
  const targets = [endpoint("place-search", "cost")];

  // When its external budget is estimated.
  const budget = invoke("estimateRiskWarningBudget", { targets, profile: "external", load: 10, extent: "10" });

  // Then setup phases and measurement are all counted.
  assert.equal(budget.targetIterations, 102);
  assert.equal(budget.maximumHttpRequests, 102);
  assert.equal(budget.maximumBillableRequests, 102);
});

test("scan v2 counts ticket and scan requests for every target iteration", () => {
  // Given the two-request scan target.
  const targets = [endpoint("scan-v2-krw", "cost", 2)];

  // When the maximum external budget is estimated.
  const budget = invoke("estimateRunBudget", { targets, profile: "external", load: 10, extent: "10" });

  // Then target iterations and HTTP calls remain separate quantities.
  assert.equal(budget.targetIterations, 102);
  assert.equal(budget.maximumHttpRequests, 204);
  assert.equal(budget.maximumBillableRequests, 204);
});

test("multi-target budget sums exact per-target request multipliers", () => {
  // Given one ordinary cost target and one scan-v2 target.
  const targets = [endpoint("place-search", "cost"), endpoint("scan-v2-usd", "cost", 2)];

  // When their external budgets are combined.
  const budget = invoke("estimateRiskWarningBudget", { targets, profile: "external", load: 10, extent: "10" });

  // Then 204 target iterations produce at most 306 HTTP and billable calls.
  assert.equal(budget.targetIterations, 204);
  assert.equal(budget.maximumHttpRequests, 306);
  assert.equal(budget.maximumBillableRequests, 306);
  assert.equal(budget.costCount, 2);
});

test("risk warning budget includes safe targets in runner-wide totals", () => {
  // Given one safe write and one fixture write selected together.
  const targets = [endpoint("safe-write"), endpoint("fixture-write", "fixture")];

  // When the adjacent risk warning model calculates write 1/30s.
  const warning = invoke("estimateRiskWarningBudget", { targets, profile: "write", load: 1, extent: "30s" });

  // Then both runner targets count, while fixture scope remains explicit.
  assert.equal(warning.targetIterations, 302);
  assert.equal(warning.maximumHttpRequests, 302);
  assert.equal(warning.maximumBillableRequests, 0);
  assert.equal(warning.fixtureTargetIterations, 151);
  assert.equal(warning.fixtureCount, 1);
  assert.equal(warning.costCount, 0);
});

test("risk warning identifies every risky target in stable key order", () => {
  // Given risky selections that are independent from the current catalog filter order.
  const targets = [
    { ...endpoint("fixture-z", "fixture"), label: "숨은 fixture" },
    { ...endpoint("safe-a"), label: "안전 대상" },
    { ...endpoint("cost-a", "cost"), label: "외부 비용" },
  ];

  // When the warning model is created from the complete selection.
  const warning = invoke("estimateRiskWarningBudget", { targets, profile: "smoke", load: 1, extent: "1" });

  // Then the exact risky identities are present and sorted by key.
  assert.deepEqual(warning.riskyTargets, [
    { key: "cost-a", label: "외부 비용", risk: "cost" },
    { key: "fixture-z", label: "숨은 fixture", risk: "fixture" },
  ]);
});

test("invalid duration and iteration boundaries never produce a budget", () => {
  // Given malformed and out-of-range extents.
  const cases = [
    { profile: "read", load: 1, extent: "0s" },
    { profile: "read", load: 1, extent: "301s" },
    { profile: "write", load: 1, extent: "121s" },
    { profile: "external", load: 10, extent: "11" },
    { profile: "external", load: 10, extent: "not-a-number" },
  ];

  // When each budget is estimated.
  // Then each invalid boundary is rejected structurally.
  for (const item of cases) {
    const budget = invoke("estimateRunBudget", { targets: [endpoint("risk", "cost")], ...item });
    assert.equal(budget.valid, false, JSON.stringify(item));
  }
});

test("configuration validation identifies the exact invalid control", () => {
  // Given configurations with one invalid boundary at a time.
  const target = { ...endpoint("fixture-a", "fixture"), defaultProfile: "write" };
  const base = { targets: [target], profile: "write", load: 1, extent: "30s", riskApproved: true, jfrEnabled: true };

  // When each configuration is validated.
  const badLoad = invoke("validateConfiguration", { ...base, load: 0 });
  const badExtent = invoke("validateConfiguration", { ...base, extent: "121s" });
  const missingApproval = invoke("validateConfiguration", { ...base, riskApproved: false });
  const valid = invoke("validateConfiguration", base);

  // Then aria-invalid can be applied only to the responsible control.
  assert.deepEqual(badLoad.invalidFields, ["load-value"]);
  assert.deepEqual(badExtent.invalidFields, ["extent-value"]);
  assert.deepEqual(missingApproval.invalidFields, ["risk-approval"]);
  assert.deepEqual(valid, { valid: true, issueCode: "", invalidFields: [] });
});

test("approval signature binds risky selection profile load and extent", () => {
  // Given the same risky targets in different display orders.
  const first = { targets: [endpoint("cost-b", "cost"), endpoint("fixture-a", "fixture")], profile: "external", load: 2, extent: "3" };
  const reordered = { ...first, targets: [...first.targets].reverse() };
  const different = { ...first, targets: [endpoint("cost-c", "cost")] };

  // When signatures are built.
  const signature = invoke("approvalSignature", first);

  // Then ordering is stable and a different risky selection cannot reuse approval.
  assert.equal(signature, invoke("approvalSignature", reordered));
  assert.notEqual(signature, invoke("approvalSignature", different));
});

test("selection profile load extent and terminal transitions clear approval", () => {
  // Given an acknowledged current signature.
  const signature = "fixture-a|write|2|30s";
  const acknowledged = invoke("transitionApproval", { approvedSignature: "", action: "acknowledge", currentSignature: signature, checked: true });

  // When any approval-scoped input or terminal state changes.
  // Then approval is cleared every time.
  assert.equal(acknowledged, signature);
  for (const action of ["selection", "profile", "load", "extent", "terminal"]) {
    assert.equal(invoke("transitionApproval", { approvedSignature: signature, action, currentSignature: signature, checked: true }), "", action);
  }
});

test("JFR disabled artifacts are complete with the three intentional files", () => {
  // Given a terminal target with report summary and manifest only.
  const artifacts = ["report.html", "summary.json", "manifest.json"].map((name) => ({ name }));

  // When completeness is evaluated for JFR disabled and enabled campaigns.
  const disabled = invoke("artifactExpectation", { jfrEnabled: false, terminal: true, artifacts });
  const enabled = invoke("artifactExpectation", { jfrEnabled: true, terminal: true, artifacts });

  // Then disabled is intentional while enabled remains partial without two JFRs.
  assert.deepEqual(disabled, { complete: true, requiredCount: 3, missingNames: [], missingJfrCount: 0, jfrExpected: false });
  assert.equal(enabled.complete, false);
  assert.equal(enabled.requiredCount, 5);
  assert.equal(enabled.missingJfrCount, 2);
});

test("comparison distinguishes missing zero baseline and comparable values", () => {
  // Given absent, zero, and positive baselines.
  // When metric comparisons are built.
  const missing = invoke("metricComparison", null, 4);
  const zero = invoke("metricComparison", 4, 0);
  const compared = invoke("metricComparison", 6, 4);

  // Then each semantic state is explicit.
  assert.deepEqual(missing, { state: "missing" });
  assert.deepEqual(zero, { state: "no-baseline", current: 4 });
  assert.deepEqual(compared, { state: "compared", current: 6, absolute: 2, percent: 50 });
});

test("initial restore selects only an active campaign", () => {
  // Given terminal-only history and mixed active history.
  const terminal = [{ campaignId: "done", status: "PASSED" }];
  const mixed = [{ campaignId: "done", status: "FAILED" }, { campaignId: "live", status: "RUNNING" }];

  // When initial active state is selected.
  // Then terminal history never populates the live panel.
  assert.equal(invoke("selectActiveCampaign", terminal), null);
  assert.equal(invoke("selectActiveCampaign", mixed).campaignId, "live");
});

test("snapshot coordinator rejects reverse-order stale active responses", async () => {
  // Given two overlapping requests whose newer response becomes terminal first.
  const pending = [deferred(), deferred()];
  let requestIndex = 0;
  let current = { campaignId: "run-1", status: "RUNNING" };
  const coordinator = invoke("createSnapshotCoordinator", {
    load: () => pending[requestIndex++].promise,
    current: () => current,
  });
  const older = coordinator.refresh("run-1");
  const newer = coordinator.refresh("run-1");

  // When the newer terminal snapshot resolves before the older active snapshot.
  pending[1].resolve({ campaignId: "run-1", status: "PASSED" });
  const accepted = await newer;
  current = accepted.campaign;
  pending[0].resolve({ campaignId: "run-1", status: "RUNNING" });
  const stale = await older;

  // Then terminal state wins and the late active response is ignored.
  assert.equal(accepted.kind, "accepted");
  assert.equal(stale.kind, "stale");
  assert.equal(current.status, "PASSED");
});

test("terminal current state dominates a late active snapshot", async () => {
  // Given a terminal campaign while a snapshot is in flight.
  const pending = deferred();
  const coordinator = invoke("createSnapshotCoordinator", {
    load: () => pending.promise,
    current: () => ({ campaignId: "run-1", status: "CANCELLED" }),
  });
  const refresh = coordinator.refresh("run-1");

  // When a late RUNNING snapshot resolves.
  pending.resolve({ campaignId: "run-1", status: "RUNNING" });

  // Then the active state cannot overwrite terminal state.
  assert.equal((await refresh).kind, "stale");
});

test("accepted SSE target state invalidates an older active snapshot", async () => {
  // Given an active snapshot request and an in-memory RUNNING target.
  const pending = deferred();
  let current = { campaignId: "run-1", status: "RUNNING", targets: [{ key: "read-a", status: "RUNNING" }] };
  const coordinator = invoke("createSnapshotCoordinator", {
    load: () => pending.promise,
    current: () => current,
  });
  const refresh = coordinator.refresh("run-1");

  // When an accepted SSE marks the target PASSED before the old GET resolves.
  const eventDecision = coordinator.acceptEvent(0, 1);
  current = { ...current, targets: [{ key: "read-a", status: "PASSED" }] };
  pending.resolve({ campaignId: "run-1", status: "RUNNING", targets: [{ key: "read-a", status: "RUNNING" }] });

  // Then the stale active snapshot cannot revert the newer target state.
  assert.equal(eventDecision.accept, true);
  assert.equal((await refresh).kind, "stale");
  assert.equal(current.targets[0].status, "PASSED");
});

test("malformed sequenced SSE retains an in-flight recovery snapshot", async () => {
  // Given an active recovery snapshot and a malformed event with a newer sequence.
  const pending = deferred();
  const coordinator = invoke("createSnapshotCoordinator", {
    load: () => pending.promise,
    current: () => ({ campaignId: "run-1", status: "RUNNING" }),
  });
  const refresh = coordinator.refresh("run-1");

  // When parsing fails before the event can be accepted.
  const parsed = invoke("parseSsePayload", "{not-json");
  if (parsed.valid) coordinator.acceptEvent(0, 1);
  pending.resolve({ campaignId: "run-1", status: "RUNNING" });

  // Then the valid recovery snapshot was never invalidated by malformed input.
  assert.equal(parsed.valid, false);
  assert.equal((await refresh).kind, "accepted");
});

test("schema-invalid SSE reuses the pending same-campaign recovery", async () => {
  // Given an in-flight recovery and a valid JSON object with the wrong schema.
  const pending = deferred();
  let loadCount = 0;
  const coordinator = invoke("createSnapshotCoordinator", {
    load: () => {
      loadCount += 1;
      return pending.promise;
    },
    current: () => ({ campaignId: "run-1", status: "RUNNING" }),
  });
  const first = coordinator.refresh("run-1");
  await Promise.resolve();

  // When the invalid event requests recovery again.
  const parsed = invoke("parseSsePayload", "{}");
  const recovery = parsed.valid ? null : coordinator.recover("run-1");
  pending.resolve({ campaignId: "run-1", status: "RUNNING" });

  // Then it retains the one pending request instead of aborting and replacing it.
  assert.equal(parsed.valid, false);
  assert.equal(recovery, first);
  assert.equal(loadCount, 1);
  assert.equal((await recovery).kind, "accepted");
});

test("SSE payload accepts only the campaign event schema", () => {
  // Given the six server RunStatus values and objects that violate one field at a time.
  const statuses = ["QUEUED", "RUNNING", "CANCELLING", "PASSED", "FAILED", "CANCELLED"];
  const invalid = [
    {},
    { target: 1, phase: "start", status: "RUNNING", line: "started" },
    { target: "read-a", phase: null, status: "RUNNING", line: "started" },
    { target: "read-a", phase: "start", status: "PARTIAL", line: "started" },
    { target: "read-a", phase: "start", status: "RUNNING", line: false },
    { target: "read-a", phase: "start", status: "RUNNING", line: "started", authorization: "secret" },
  ];

  // When payloads are parsed.
  // Then only exact typed CampaignEvent documents are accepted.
  for (const status of statuses) {
    const document = { target: "", phase: "campaign", status, line: "state" };
    assert.deepEqual(invoke("parseSsePayload", JSON.stringify(document)), { valid: true, payload: document });
  }
  for (const document of invalid) assert.equal(invoke("parseSsePayload", JSON.stringify(document)).valid, false);
});

test("stream scope resets only when the campaign identity changes", () => {
  // Given reconnects and campaign switches.
  // When stream scope decisions are calculated.
  const reconnect = invoke("streamScopeDecision", "run-1", "run-1");
  const switchCampaign = invoke("streamScopeDecision", "run-1", "run-2");
  const firstCampaign = invoke("streamScopeDecision", "", "run-1");

  // Then reconnect preserves sequence/console state while open and switch reset it.
  assert.deepEqual(reconnect, { campaignId: "run-1", reset: false });
  assert.deepEqual(switchCampaign, { campaignId: "run-2", reset: true });
  assert.deepEqual(firstCampaign, { campaignId: "run-1", reset: true });
});

test("SSE replay reconnect backoff and cancel decisions are deterministic", () => {
  // Given current sequence, reconnect delay, and campaign states.
  // When decisions are calculated.
  const replay = invoke("sseSequenceDecision", 7, 7);
  const next = invoke("sseSequenceDecision", 7, 8);
  const reconnect = invoke("reconnectDecision", { status: "RUNNING", timerPending: false, delay: 8000 });

  // Then replay is ignored, delay caps at ten seconds, and cancel stays single-shot.
  assert.deepEqual(replay, { accept: false, sequence: 7 });
  assert.deepEqual(next, { accept: true, sequence: 8 });
  assert.deepEqual(reconnect, { schedule: true, delay: 8000, nextDelay: 10000 });
  assert.equal(invoke("reconnectDecision", { status: "PASSED", timerPending: false, delay: 1000 }).schedule, false);
  assert.equal(invoke("canCancel", { campaign: { status: "RUNNING" }, cancelSent: false }), true);
  assert.equal(invoke("canCancel", { campaign: { status: "RUNNING" }, cancelSent: true }), false);
  assert.equal(invoke("canCancel", { campaign: { status: "FAILED" }, cancelSent: false }), false);
});

test("payload construction preserves exact Task 9 API modes", () => {
  // Given safe-all and explicitly selected configurations.
  // When payloads are built.
  const safeAll = invoke("buildRunPayload", { mode: "safe-all" });
  const selected = invoke("buildRunPayload", {
    mode: "selected",
    selectedKeys: ["cost-a", "scan-v2-krw"],
    profile: "external",
    load: 10,
    extent: "10",
    riskApproved: true,
    jfrEnabled: true,
  });

  // Then no invented fields or API modes appear.
  assert.deepEqual(safeAll, { mode: "safe-all", profile: "smoke", rateOrVus: 1, durationOrIterations: "1", jfrEnabled: true });
  assert.deepEqual(selected, {
    mode: "selected",
    targetKeys: ["cost-a", "scan-v2-krw"],
    profile: "external",
    rateOrVus: 10,
    durationOrIterations: "10",
    allowRisk: true,
    jfrEnabled: true,
  });
});

test("catalog selection actions can clear defaults before a risky selection", () => {
  // Given the default safe selection loaded from the catalog.
  const targets = [
    { ...endpoint("read-a"), defaultEnabled: true, defaultProfile: "read" },
    { ...endpoint("write-a"), defaultEnabled: true, defaultProfile: "write" },
    { ...endpoint("cost-a", "cost"), defaultEnabled: false, defaultProfile: "external" },
  ];

  // When the operator explicitly clears the selection.
  const cleared = invoke("selectionForAction", { targets, profile: "external", action: "clear" });

  // Then every hidden/default target is removed, independent of active filters.
  assert.deepEqual(cleared, []);
});
