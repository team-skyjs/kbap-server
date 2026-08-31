"use strict";

export const ACTIVE_STATUSES = new Set(["QUEUED", "RUNNING", "CANCELLING"]);
export const TERMINAL_STATUSES = new Set(["PASSED", "FAILED", "CANCELLED"]);

const PROFILE_RULES = Object.freeze({
  smoke: Object.freeze({ loadKind: "fixed", loadLabel: "동시 부하", loadHelp: "smoke 부하는 1로 고정됩니다.", maxLoad: 1, extentKind: "iterations", extentLabel: "반복 횟수", extentHelp: "smoke 반복 횟수는 1입니다.", extent: "1", extentInputMode: "numeric", maxExtent: 1 }),
  read: Object.freeze({ loadKind: "rate", loadLabel: "초당 요청률", loadHelp: "read 요청률 상한은 초당 40회입니다.", maxLoad: 40, extentKind: "duration", extentLabel: "지속 시간", extentHelp: "1s부터 300s 또는 5m까지 입력합니다.", extent: "30s", extentInputMode: "text", maxExtent: 300 }),
  write: Object.freeze({ loadKind: "rate", loadLabel: "초당 요청률", loadHelp: "write 요청률 상한은 초당 10회입니다.", maxLoad: 10, extentKind: "duration", extentLabel: "지속 시간", extentHelp: "1s부터 120s 또는 2m까지 입력합니다.", extent: "30s", extentInputMode: "text", maxExtent: 120 }),
  external: Object.freeze({ loadKind: "vus", loadLabel: "가상 사용자 수", loadHelp: "external VU 상한은 10입니다.", maxLoad: 10, extentKind: "iterations", extentLabel: "반복 횟수", extentHelp: "비용 보호를 위해 1회부터 10회까지만 허용됩니다.", extent: "1", extentInputMode: "numeric", maxExtent: 10 }),
});

const APPROVAL_MUTATIONS = new Set(["selection", "profile", "load", "extent", "terminal"]);
const CORE_ARTIFACTS = Object.freeze(["report.html", "summary.json", "manifest.json"]);
const RUN_STATUSES = new Set(["QUEUED", "RUNNING", "CANCELLING", "PASSED", "FAILED", "CANCELLED"]);
const SSE_FIELDS = Object.freeze(["line", "phase", "status", "target"]);

export function profileRule(profile) {
  return PROFILE_RULES[profile] || null;
}

export function parseDurationSeconds(raw) {
  const match = /^([1-9][0-9]*)([sm])$/.exec(raw);
  if (!match) return null;
  return Number(match[1]) * (match[2] === "m" ? 60 : 1);
}

function extentValue(profile, raw) {
  const rule = profileRule(profile);
  if (!rule) return null;
  if (rule.extentKind === "duration") return parseDurationSeconds(raw);
  return /^[1-9][0-9]*$/.test(raw) ? Number(raw) : null;
}

function perTargetIterations(profile, load, extent) {
  const rule = profileRule(profile);
  const parsedExtent = extentValue(profile, extent);
  if (!rule || !Number.isInteger(load) || load < 1 || load > rule.maxLoad || parsedExtent === null || parsedExtent > rule.maxExtent) return null;
  if (profile === "smoke") return load === 1 && parsedExtent === 1 ? 3 : null;
  if (profile === "read" || profile === "write") return 1 + load * (120 + parsedExtent);
  return 2 + load * parsedExtent;
}

export function estimateRunBudget({ targets, profile, load, extent }) {
  const iterations = perTargetIterations(profile, load, extent);
  const multipliersValid = targets.every((target) => Number.isInteger(target.requestsPerIteration) && target.requestsPerIteration >= 1);
  if (iterations === null || !multipliersValid) return { valid: false, reason: "invalid-run-budget" };
  return {
    valid: true,
    targetIterations: iterations * targets.length,
    maximumHttpRequests: targets.reduce((total, target) => total + iterations * target.requestsPerIteration, 0),
    maximumBillableRequests: targets.filter((target) => target.risk === "cost").reduce((total, target) => total + iterations * target.requestsPerIteration, 0),
    fixtureTargetIterations: iterations * targets.filter((target) => target.risk === "fixture").length,
  };
}

export function estimateRiskWarningBudget(configuration) {
  const budget = estimateRunBudget(configuration);
  const riskyTargets = configuration.targets
    .filter((target) => target.risk !== "safe")
    .map((target) => ({ key: target.key, label: target.label, risk: target.risk }))
    .sort((left, right) => left.key.localeCompare(right.key));
  return {
    ...budget,
    fixtureCount: riskyTargets.filter((target) => target.risk === "fixture").length,
    costCount: riskyTargets.filter((target) => target.risk === "cost").length,
    riskyTargets,
  };
}

function compatibleWithProfile(targets, profile) {
  return profile === "smoke" || targets.every((target) => target.defaultProfile === profile);
}

export function validateConfiguration({ targets, profile, load, extent, riskApproved, jfrEnabled }) {
  const rule = profileRule(profile);
  if (!targets.length) return { valid: false, issueCode: "empty-target-selection", invalidFields: [] };
  if (!rule || !compatibleWithProfile(targets, profile)) return { valid: false, issueCode: "profile-target-mismatch", invalidFields: ["profile"] };
  if (!Number.isInteger(load) || load < 1 || load > rule.maxLoad) return { valid: false, issueCode: "invalid-rate-or-vus", invalidFields: ["load-value"] };
  const parsedExtent = extentValue(profile, extent);
  if (parsedExtent === null || parsedExtent > rule.maxExtent) return { valid: false, issueCode: "invalid-duration-or-iterations", invalidFields: ["extent-value"] };
  if (profile === "smoke" && parsedExtent !== 1) return { valid: false, issueCode: "invalid-duration-or-iterations", invalidFields: ["extent-value"] };
  if (targets.some((target) => target.risk !== "safe") && !riskApproved) return { valid: false, issueCode: "risk-approval-required", invalidFields: ["risk-approval"] };
  if (!jfrEnabled && (profile !== "smoke" || targets.length !== 1)) return { valid: false, issueCode: "jfr-off-requires-single-smoke", invalidFields: ["jfr-enabled"] };
  return { valid: true, issueCode: "", invalidFields: [] };
}

export function approvalSignature({ targets, profile, load, extent }) {
  const riskyKeys = targets.filter((target) => target.risk !== "safe").map((target) => target.key).sort();
  return riskyKeys.length ? `${riskyKeys.join(",")}|${profile}|${load}|${extent}` : "";
}

export function transitionApproval({ approvedSignature, action, currentSignature, checked }) {
  if (action === "acknowledge") return checked ? currentSignature : "";
  return APPROVAL_MUTATIONS.has(action) ? "" : approvedSignature;
}

export function artifactExpectation({ jfrEnabled, artifacts }) {
  const names = new Set(artifacts.map((artifact) => artifact.name));
  const missingNames = CORE_ARTIFACTS.filter((name) => !names.has(name));
  const jfrCount = artifacts.filter((artifact) => artifact.name.endsWith(".jfr")).length;
  const missingJfrCount = jfrEnabled ? Math.max(0, 2 - jfrCount) : 0;
  return {
    complete: missingNames.length === 0 && missingJfrCount === 0,
    requiredCount: jfrEnabled ? 5 : 3,
    missingNames,
    missingJfrCount,
    jfrExpected: jfrEnabled,
  };
}

export function metricComparison(current, previous) {
  if (current === null || current === undefined) return { state: "missing" };
  if (previous === null || previous === undefined || previous === 0) return { state: "no-baseline", current };
  return { state: "compared", current, absolute: current - previous, percent: ((current - previous) / previous) * 100 };
}

export function selectActiveCampaign(runs) {
  return runs.find((campaign) => ACTIVE_STATUSES.has(campaign.status)) || null;
}

function snapshotIsCurrent(current, candidate, generation, latestGeneration) {
  if (generation !== latestGeneration || candidate.campaignId !== current?.campaignId) return false;
  return !(TERMINAL_STATUSES.has(current.status) && ACTIVE_STATUSES.has(candidate.status));
}

export function createSnapshotCoordinator({ load, current }) {
  let generation = 0;
  let controller = null;
  let pendingCampaignId = "";
  let pendingPromise = null;
  function invalidate() {
    generation += 1;
    controller?.abort();
    controller = null;
    pendingCampaignId = "";
    pendingPromise = null;
  }
  function acceptEvent(lastSequence, incomingSequence) {
    const decision = sseSequenceDecision(lastSequence, incomingSequence);
    if (decision.accept) invalidate();
    return decision;
  }
  function refresh(campaignId) {
    generation += 1;
    const requestGeneration = generation;
    controller?.abort();
    const requestController = new AbortController();
    controller = requestController;
    pendingCampaignId = campaignId;
    const request = (async () => {
      try {
        const campaign = await Promise.resolve().then(() => load(campaignId, requestController.signal));
        if (!snapshotIsCurrent(current(campaignId), campaign, requestGeneration, generation)) return { kind: "stale" };
        return { kind: "accepted", campaign };
      } catch (error) {
        if (error?.name === "AbortError") return { kind: "stale" };
        return { kind: "error", error };
      } finally {
        if (requestGeneration === generation) {
          controller = null;
          pendingCampaignId = "";
          pendingPromise = null;
        }
      }
    })();
    pendingPromise = request;
    return request;
  }
  function recover(campaignId) {
    return pendingPromise && pendingCampaignId === campaignId ? pendingPromise : refresh(campaignId);
  }
  return {
    refresh,
    recover,
    acceptEvent,
    invalidate,
    abort: invalidate,
  };
}

export function sseSequenceDecision(lastSequence, incomingSequence) {
  if (!incomingSequence || incomingSequence <= lastSequence) return { accept: false, sequence: lastSequence };
  return { accept: true, sequence: incomingSequence };
}

export function parseSsePayload(serializedPayload) {
  try {
    const payload = JSON.parse(serializedPayload);
    if (payload === null || typeof payload !== "object" || Array.isArray(payload)) return { valid: false, payload: null };
    const fields = Object.keys(payload).sort();
    if (fields.length !== SSE_FIELDS.length || fields.some((field, index) => field !== SSE_FIELDS[index])) return { valid: false, payload: null };
    if (typeof payload.target !== "string" || typeof payload.phase !== "string" || typeof payload.line !== "string" || !RUN_STATUSES.has(payload.status)) {
      return { valid: false, payload: null };
    }
    return { valid: true, payload };
  } catch {
    return { valid: false, payload: null };
  }
}

export function streamScopeDecision(currentCampaignId, nextCampaignId) {
  return { campaignId: nextCampaignId, reset: currentCampaignId !== nextCampaignId };
}

export function reconnectDecision({ status, timerPending, delay }) {
  if (!ACTIVE_STATUSES.has(status) || timerPending) return { schedule: false, delay, nextDelay: delay };
  return { schedule: true, delay, nextDelay: Math.min(delay * 2, 10000) };
}

export function canCancel({ campaign, cancelSent }) {
  return Boolean(campaign && ACTIVE_STATUSES.has(campaign.status) && !cancelSent && campaign.status !== "CANCELLING");
}

export function buildRunPayload(configuration) {
  if (configuration.mode === "safe-all") {
    return { mode: "safe-all", profile: "smoke", rateOrVus: 1, durationOrIterations: "1", jfrEnabled: true };
  }
  return {
    mode: "selected",
    targetKeys: configuration.selectedKeys,
    profile: configuration.profile,
    rateOrVus: configuration.load,
    durationOrIterations: configuration.extent,
    allowRisk: configuration.riskApproved,
    jfrEnabled: configuration.jfrEnabled,
  };
}

export function selectionForAction({ targets, profile, action }) {
  if (action === "clear") return [];
  if (action === "safe-defaults") {
    return targets
      .filter((target) => target.defaultEnabled && target.risk === "safe" && (profile === "smoke" || target.defaultProfile === profile))
      .map((target) => target.key);
  }
  throw new TypeError(`unsupported selection action: ${action}`);
}
