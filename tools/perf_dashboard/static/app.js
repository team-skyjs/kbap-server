"use strict";

import {
  ACTIVE_STATUSES,
  TERMINAL_STATUSES,
  approvalSignature,
  artifactExpectation,
  buildRunPayload,
  canCancel,
  createSnapshotCoordinator,
  estimateRiskWarningBudget,
  metricComparison,
  parseSsePayload,
  profileRule,
  reconnectDecision,
  selectionForAction,
  selectActiveCampaign,
  streamScopeDecision,
  transitionApproval,
  validateConfiguration,
} from "/model.mjs";

const PROFILE_COPY = {
  smoke: "smoke, warmup, measurement에서 target을 한 번씩 실행해 연결, fixture, JFR 계약을 검증합니다.",
  read: "read는 초당 요청률과 지속 시간으로 읽기 endpoint 처리량을 측정합니다.",
  write: "write는 초당 요청률과 지속 시간으로 쓰기 endpoint를 측정합니다.",
  external: "external은 제한된 VU와 반복 횟수로 비용 발생 endpoint를 측정합니다.",
};
const ERROR_COPY = {
  "active-campaign-exists": "다른 campaign이 실행 중입니다. 현재 campaign을 복구해 상태를 확인하세요.",
  "empty-target-selection": "실행할 target을 하나 이상 선택하세요.",
  "profile-target-mismatch": "선택한 target suite와 프로파일이 맞지 않습니다. smoke를 사용하거나 같은 프로파일 target만 선택하세요.",
  "risk-approval-required": "fixture 또는 cost target은 위험 확인이 필요합니다.",
  "invalid-rate-or-vus": "부하 값이 프로파일 상한을 벗어났습니다.",
  "invalid-duration-or-iterations": "지속 시간 또는 반복 횟수 형식을 확인하세요.",
  "jfr-off-requires-single-smoke": "JFR 해제는 단일 target smoke에서만 허용됩니다.",
  "campaign-not-active": "이미 종료된 campaign은 취소할 수 없습니다.",
  "campaign-not-found": "campaign 기록을 찾을 수 없습니다.",
};
const MAX_CONSOLE_LINES = 100;

class RequestError extends Error {
  constructor(status, code) {
    super(code);
    this.name = "RequestError";
    this.status = status;
    this.code = code;
  }
}

const state = {
  targets: [],
  runs: [],
  selectedKeys: new Set(),
  selectedRunId: "",
  liveCampaign: null,
  phaseByTarget: new Map(),
  consoleLines: [],
  lastSequence: 0,
  reconnectDelay: 1000,
  reconnectTimer: null,
  eventSource: null,
  cancelSent: false,
  approvedRiskSignature: "",
  reconnectPending: false,
  streamCampaignId: "",
  loading: true,
};

const dom = Object.fromEntries([
  "header-status", "app-error", "selection-count", "safe-select-button", "clear-selection-button", "target-search", "suite-filter", "risk-filter", "target-list",
  "profile", "profile-help", "load-label", "load-value", "load-help", "extent-label", "extent-value", "extent-help", "jfr-enabled", "jwt-secret",
  "risk-approval-row", "risk-approval", "risk-warning", "configuration-error", "safe-all-button", "selected-run-button", "cancel-button",
  "live-title", "live-status", "elapsed-time", "active-summary", "active-targets", "console-output", "history-select", "results-body",
  "artifact-state", "bundle-download", "artifact-list",
].map((id) => [id, document.getElementById(id)]));

const snapshots = createSnapshotCoordinator({
  load: (campaignId, signal) => fetchJson(`/api/runs/${encodeURIComponent(campaignId)}`, { signal }),
  current: (campaignId) => state.runs.find((campaign) => campaign.campaignId === campaignId) || state.liveCampaign,
});

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function replaceChildren(node, children) {
  node.replaceChildren(...children);
}

function setAppError(message) {
  dom["app-error"].hidden = !message;
  dom["app-error"].textContent = message || "";
}

function requestMessage(error, fallback) {
  if (error instanceof RequestError) return ERROR_COPY[error.code] || `${fallback} 서버 응답: ${error.status} ${error.code}`;
  return `${fallback} localhost 서버 연결을 확인하세요.`;
}

async function fetchJson(path, options) {
  const response = await fetch(path, options);
  let documentBody = {};
  try {
    documentBody = await response.json();
  } catch (error) {
    if (error instanceof SyntaxError) documentBody = { error: "invalid-response" };
    else throw error;
  }
  if (!response.ok) throw new RequestError(response.status, documentBody.error || "request-failed");
  return documentBody;
}

function isActive(campaign) {
  return Boolean(campaign && ACTIVE_STATUSES.has(campaign.status));
}

function formatDate(value) {
  if (!value) return "시간 없음";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "시간 형식 오류" : date.toLocaleString("ko-KR", { hour12: false });
}

function formatElapsed(campaign) {
  if (!campaign) return "00:00";
  const start = new Date(campaign.startedAt || campaign.createdAt).getTime();
  const finish = campaign.finishedAt ? new Date(campaign.finishedAt).getTime() : Date.now();
  const seconds = Math.max(0, Math.floor((finish - start) / 1000));
  const minutes = Math.floor(seconds / 60).toString().padStart(2, "0");
  const remainder = (seconds % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}

function statusClass(status) {
  if (status === "PASSED") return "status status-passed";
  if (status === "FAILED") return "status status-failed";
  if (status === "RUNNING" || status === "CANCELLING") return "status status-running";
  if (status === "CANCELLED") return "status status-warning";
  return "status";
}

function targetMatches(target) {
  const query = dom["target-search"].value.trim().toLocaleLowerCase("ko-KR");
  const suite = dom["suite-filter"].value;
  const risk = dom["risk-filter"].value;
  const searchable = `${target.label} ${target.key} ${target.route} ${target.method}`.toLocaleLowerCase("ko-KR");
  return (!query || searchable.includes(query)) && (suite === "all" || target.suite === suite) && (risk === "all" || target.risk === risk);
}

function selectedTargets() {
  return state.targets.filter((target) => state.selectedKeys.has(target.key));
}

function renderCatalog() {
  const locked = isActive(state.liveCampaign);
  const targets = state.targets.filter(targetMatches);
  const rows = targets.map((target) => {
    const label = element("label", "endpoint-row");
    if (state.selectedKeys.has(target.key)) label.classList.add("is-selected");
    if (locked) {
      label.classList.add("is-disabled");
      label.setAttribute("aria-disabled", "true");
    }
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = state.selectedKeys.has(target.key);
    input.disabled = locked;
    input.dataset.targetKey = target.key;
    input.addEventListener("change", () => {
      clearApproval("selection");
      if (input.checked) state.selectedKeys.add(target.key);
      else state.selectedKeys.delete(target.key);
      renderCatalog();
      renderConfiguration();
    });
    const main = element("span", "endpoint-main");
    const title = element("span", "endpoint-title");
    title.append(element("span", "method-badge", target.method), element("strong", "", target.label));
    const meta = element("span", "endpoint-meta");
    meta.append(element("code", "endpoint-route", target.route), element("span", "", target.suite));
    const risk = element("span", target.risk === "safe" ? "risk-safe" : "risk-label", target.risk);
    main.append(title, meta);
    label.append(input, main, risk);
    return label;
  });
  if (!rows.length) rows.push(element("p", "empty-surface", state.targets.length ? "일치하는 endpoint 없음. 검색어 또는 필터를 바꾸세요." : "Endpoint catalog가 비어 있습니다."));
  replaceChildren(dom["target-list"], rows);
  dom["target-list"].setAttribute("aria-busy", state.loading ? "true" : "false");
  dom["selection-count"].textContent = `${state.selectedKeys.size}개 선택, ${targets.length}개 표시`;
}

function currentRiskSignature(targets = selectedTargets()) {
  return approvalSignature({
    targets,
    profile: dom.profile.value,
    load: Number(dom["load-value"].value),
    extent: dom["extent-value"].value.trim(),
  });
}

function clearApproval(action) {
  state.approvedRiskSignature = transitionApproval({
    approvedSignature: state.approvedRiskSignature,
    action,
    currentSignature: currentRiskSignature(),
    checked: dom["risk-approval"].checked,
  });
  dom["risk-approval"].checked = false;
}

function configurationMessage(validation, rule) {
  if (validation.valid) return "";
  if (validation.issueCode === "invalid-rate-or-vus") return `부하 값은 1부터 ${rule.maxLoad}까지 입력하세요.`;
  if (validation.issueCode === "invalid-duration-or-iterations") return rule.extentHelp;
  return ERROR_COPY[validation.issueCode] || "실행 설정을 확인하세요.";
}

function renderConfiguration() {
  const locked = isActive(state.liveCampaign);
  const profile = dom.profile.value;
  const rule = profileRule(profile);
  const targets = selectedTargets();
  const risky = targets.filter((target) => target.risk !== "safe");
  dom["load-label"].textContent = rule.loadLabel;
  dom["load-help"].textContent = rule.loadHelp;
  dom["extent-label"].textContent = rule.extentLabel;
  dom["extent-help"].textContent = rule.extentHelp;
  dom["profile-help"].textContent = PROFILE_COPY[profile];
  dom["load-value"].max = String(rule.maxLoad);
  dom["extent-value"].inputMode = rule.extentInputMode;
  dom["risk-approval-row"].hidden = risky.length === 0;
  dom["risk-warning"].hidden = risky.length === 0;
  if (risky.length) {
    const budget = estimateRiskWarningBudget({
      targets,
      profile,
      load: Number(dom["load-value"].value),
      extent: dom["extent-value"].value.trim(),
    });
    const { fixtureCount, costCount } = budget;
    const fixtureNote = fixtureCount ? " 고유 fixture가 소진되면 실제 쓰기 요청은 이 상한보다 적습니다." : "";
    const riskTargetNodes = [];
    budget.riskyTargets.forEach((target, index) => {
      if (index) riskTargetNodes.push(element("span", "risk-target-separator", ", "));
      const identity = element("span", "risk-target-identity");
      replaceChildren(identity, [
        element("span", "", `${target.label} [`),
        element("code", "risk-target-key", target.key),
        element("span", "", "]"),
      ]);
      riskTargetNodes.push(identity);
    });
    const riskTargetList = element("span", "risk-target-list");
    replaceChildren(riskTargetList, riskTargetNodes);
    const summary = budget.valid
      ? `. fixture ${fixtureCount}개, cost ${costCount}개. Runner 전체 단계의 target iteration ${budget.targetIterations}회, 최대 HTTP request ${budget.maximumHttpRequests}회, 외부 provider billable request 최대 ${budget.maximumBillableRequests}회.${fixtureNote}`
      : `. fixture ${fixtureCount}개, cost ${costCount}개. 유효한 부하와 범위를 입력하면 전체 runner 단계의 request 상한을 계산합니다.${fixtureNote}`;
    replaceChildren(dom["risk-warning"], [element("span", "", "위험 대상: "), riskTargetList, element("span", "", summary)]);
  }
  const jfrCanChange = !locked && profile === "smoke" && targets.length === 1;
  dom["jfr-enabled"].disabled = !jfrCanChange;
  if (!jfrCanChange) dom["jfr-enabled"].checked = true;
  const signature = currentRiskSignature(targets);
  const riskApproved = Boolean(signature && dom["risk-approval"].checked && state.approvedRiskSignature === signature);
  if (!risky.length || (dom["risk-approval"].checked && !riskApproved)) {
    state.approvedRiskSignature = "";
    dom["risk-approval"].checked = false;
  }
  const validation = validateConfiguration({
    targets,
    profile,
    load: Number(dom["load-value"].value),
    extent: dom["extent-value"].value.trim(),
    riskApproved,
    jfrEnabled: dom["jfr-enabled"].checked,
  });
  for (const id of ["profile", "load-value", "extent-value", "risk-approval", "jfr-enabled"]) {
    dom[id].setAttribute("aria-invalid", locked ? "false" : String(validation.invalidFields.includes(id)));
  }
  const issue = locked ? "실행 중에는 target과 설정을 변경할 수 없습니다." : configurationMessage(validation, rule);
  dom["configuration-error"].textContent = issue;
  dom["selected-run-button"].disabled = locked || Boolean(issue);
  dom["safe-all-button"].disabled = locked || state.loading || !state.targets.some((target) => target.defaultEnabled && target.risk === "safe");
  dom["clear-selection-button"].disabled = locked || state.loading || state.selectedKeys.size === 0;
  dom["cancel-button"].disabled = !locked || state.cancelSent || state.liveCampaign?.status === "CANCELLING";
  for (const id of ["profile", "load-value", "extent-value", "risk-approval"]) dom[id].disabled = locked;
  for (const id of ["target-search", "suite-filter", "risk-filter", "safe-select-button"]) dom[id].disabled = locked || state.loading;
  document.querySelectorAll(".endpoint-row input").forEach((input) => { input.disabled = locked; });
}

function renderLive() {
  const campaign = state.liveCampaign;
  dom["elapsed-time"].textContent = formatElapsed(campaign);
  if (!campaign) {
    dom["live-status"].textContent = "활성 캠페인이 없습니다.";
    dom["header-status"].className = "status";
    dom["header-status"].textContent = state.loading ? "초기화 중" : "실행 대기";
    dom["active-summary"].className = "campaign-strip empty-surface";
    dom["active-summary"].textContent = "실행하면 campaign ID, phase, target 상태가 여기에 표시됩니다.";
    replaceChildren(dom["active-targets"], []);
    dom["console-output"].textContent = "console 대기 중";
    return;
  }
  const statusText = `${campaign.status} ${isActive(campaign) ? "진행 중" : "종료"}`;
  dom["live-status"].textContent = `${statusText}. ${campaign.targets.length}개 target. ${formatDate(campaign.createdAt)}`;
  dom["header-status"].className = statusClass(campaign.status);
  dom["header-status"].textContent = statusText;
  dom["active-summary"].className = "campaign-strip";
  const summaryParts = [
    element("code", "campaign-id", campaign.campaignId),
    element("span", statusClass(campaign.status), campaign.status),
    element("span", "", `${campaign.profile} / ${campaign.rateOrVus} / ${campaign.durationOrIterations}`),
  ];
  if (campaign.failureReason) summaryParts.push(element("span", "error-text", `실패 사유: ${campaign.failureReason}`));
  replaceChildren(dom["active-summary"], summaryParts);
  const rows = campaign.targets.map((target) => {
    const item = element("li", "active-target-row");
    const details = element("span", "");
    const targetMeta = state.targets.find((candidate) => candidate.key === target.key);
    details.append(element("strong", "", targetMeta?.label || target.key), element("code", "", target.key));
    const phase = state.phaseByTarget.get(target.key) || (target.status === "QUEUED" ? "queue" : "snapshot");
    item.append(details, element("span", "phase-label", `phase=${phase}`), element("span", statusClass(target.status), target.status));
    return item;
  });
  replaceChildren(dom["active-targets"], rows);
  dom["console-output"].textContent = state.consoleLines.length ? state.consoleLines.join("\n") : "snapshot 복구됨. 새 SSE console event 대기 중";
}

function campaignOption(campaign) {
  const label = `${campaign.campaignId} | ${campaign.status} | ${formatDate(campaign.createdAt)}`;
  const option = element("option", "", label);
  option.value = campaign.campaignId;
  return option;
}

function renderHistoryPicker() {
  const options = state.runs.map(campaignOption);
  if (!options.length) {
    const empty = element("option", "", "기록 없음");
    empty.value = "";
    options.push(empty);
  }
  replaceChildren(dom["history-select"], options);
  dom["history-select"].disabled = state.runs.length === 0;
  if (!state.selectedRunId || !state.runs.some((run) => run.campaignId === state.selectedRunId)) state.selectedRunId = state.runs[0]?.campaignId || "";
  dom["history-select"].value = state.selectedRunId;
}

function metricDelta(current, previous, unit, scale = 1) {
  const comparison = metricComparison(current, previous);
  if (comparison.state === "missing") return "데이터 없음";
  const formatted = `${(comparison.current * scale).toFixed(scale === 100 ? 2 : 1)}${unit}`;
  if (comparison.state === "no-baseline") return `${formatted} | 비교 기준 없음`;
  const absolute = comparison.absolute * scale;
  const percent = comparison.percent;
  const sign = absolute > 0 ? "+" : "";
  const percentSign = percent > 0 ? "+" : "";
  return `${formatted} | ${sign}${absolute.toFixed(scale === 100 ? 2 : 1)}${unit} (${percentSign}${percent.toFixed(1)}%)`;
}

function previousTarget(campaign, key) {
  const currentTime = new Date(campaign.createdAt).getTime();
  for (const previous of state.runs) {
    if (previous.campaignId === campaign.campaignId || new Date(previous.createdAt).getTime() >= currentTime) continue;
    const target = previous.targets.find((candidate) => candidate.key === key && candidate.summary);
    if (target) return target;
  }
  return null;
}

function tableCell(label, text, className = "", tag = "td") {
  const cell = element(tag, className);
  cell.dataset.label = label;
  cell.textContent = text;
  return cell;
}

function renderResults() {
  const campaign = state.runs.find((run) => run.campaignId === state.selectedRunId);
  if (!campaign) {
    const row = document.createElement("tr");
    const cell = tableCell("결과", state.loading ? "결과 기록을 불러오는 중입니다." : "아직 실행 기록이 없습니다.");
    cell.colSpan = 6;
    row.append(cell);
    replaceChildren(dom["results-body"], [row]);
    renderArtifacts(null);
    return;
  }
  const rows = campaign.targets.map((target) => {
    const row = document.createElement("tr");
    const targetMeta = state.targets.find((candidate) => candidate.key === target.key);
    const previous = previousTarget(campaign, target.key);
    const summary = target.summary;
    const name = tableCell("Target", targetMeta?.label || target.key, "target-cell", "th");
    name.scope = "row";
    name.append(element("code", "", target.key), element("span", statusClass(target.status), target.status));
    row.append(
      name,
      tableCell("p95", metricDelta(summary?.p95, previous?.summary?.p95, " ms"), "result-value"),
      tableCell("p99", metricDelta(summary?.p99, previous?.summary?.p99, " ms"), "result-value"),
      tableCell("실패율", metricDelta(summary?.failureRate, previous?.summary?.failureRate, "%", 100), "result-value"),
      tableCell("Dropped", metricDelta(summary?.droppedIterations, previous?.summary?.droppedIterations, ""), "result-value"),
      tableCell("Threshold", summary?.thresholdsPassed === true ? "통과" : summary?.thresholdsPassed === false ? "실패" : "데이터 없음", summary?.thresholdsPassed === false ? "threshold-failed" : ""),
    );
    return row;
  });
  if (!rows.length) {
    const row = document.createElement("tr");
    const cell = tableCell("결과", "이 campaign에는 target이 없습니다.");
    cell.colSpan = 6;
    row.append(cell);
    rows.push(row);
  }
  replaceChildren(dom["results-body"], rows);
  renderArtifacts(campaign);
}

function artifactLink(campaign, artifact, label) {
  const link = element("a", "download-link", label);
  link.href = `/api/runs/${encodeURIComponent(campaign.campaignId)}/artifacts/${encodeURIComponent(artifact.id)}`;
  if (artifact.name === "report.html") {
    link.target = "_blank";
    link.rel = "noopener";
  }
  return link;
}

function renderArtifacts(campaign) {
  if (!campaign) {
    dom["artifact-state"].textContent = "campaign을 선택하면 로컬 artifact 링크가 표시됩니다.";
    replaceChildren(dom["bundle-download"], []);
    replaceChildren(dom["artifact-list"], []);
    return;
  }
  const terminal = TERMINAL_STATUSES.has(campaign.status);
  if (terminal) {
    const bundle = element("a", "button download-link", "전체 artifact ZIP");
    bundle.href = `/api/runs/${encodeURIComponent(campaign.campaignId)}/bundle`;
    replaceChildren(dom["bundle-download"], [bundle]);
  } else {
    replaceChildren(dom["bundle-download"], [element("span", "download-unavailable", "ZIP 준비 중")]);
  }
  let partialCount = 0;
  const groups = campaign.targets.map((target) => {
    const group = element("section", "artifact-target");
    const title = element("h4", "artifact-target-key", target.key);
    const artifacts = target.artifacts || [];
    const byName = new Map(artifacts.map((artifact) => [artifact.name, artifact]));
    const links = element("div", "artifact-links");
    const expected = [
      ["report.html", "HTML 결과 보기"],
      ["summary.json", "summary.json"],
      ["manifest.json", "manifest.json"],
    ];
    for (const [name, label] of expected) {
      const artifact = byName.get(name);
      links.append(artifact ? artifactLink(campaign, artifact, label) : element("span", "download-unavailable", `${label} ${terminal ? "없음" : "수집 중"}`));
    }
    const jfrs = artifacts.filter((artifact) => artifact.name.endsWith(".jfr"));
    if (campaign.jfrEnabled) {
      for (const artifact of jfrs) links.append(artifactLink(campaign, artifact, artifact.name));
      for (let index = jfrs.length; index < 2; index += 1) links.append(element("span", "download-unavailable", `task JFR ${index + 1} ${terminal ? "없음" : "수집 중"}`));
    } else {
      links.append(element("span", "download-unavailable jfr-disabled", "JFR 수집 안 함"));
    }
    const expectation = artifactExpectation({ jfrEnabled: campaign.jfrEnabled, terminal, artifacts });
    if (terminal && !expectation.complete) partialCount += 1;
    const collectionText = expectation.complete
      ? `필수 artifact ${expectation.requiredCount}개 준비됨${campaign.jfrEnabled ? "" : ". JFR 수집 안 함"}`
      : terminal ? "부분 수집 상태" : "수집 중";
    group.append(title, element("p", expectation.complete ? "help" : "error-text", collectionText), links);
    return group;
  });
  dom["artifact-state"].textContent = partialCount
    ? `${partialCount}개 target이 부분 수집 상태입니다. JFR 수집 캠페인은 manifest와 두 task JFR을 함께 확인하세요.`
    : terminal && !campaign.jfrEnabled ? "JFR 수집 안 함 캠페인입니다. report, summary, manifest만 필수입니다."
      : terminal ? "수집된 artifact는 localhost allowlist route로만 제공됩니다."
        : "실행 중이며 artifact 수집 상태를 갱신합니다.";
  replaceChildren(dom["artifact-list"], groups);
}

function renderAll() {
  renderCatalog();
  renderConfiguration();
  renderLive();
  renderHistoryPicker();
  renderResults();
}

function upsertRun(campaign) {
  const index = state.runs.findIndex((run) => run.campaignId === campaign.campaignId);
  if (index >= 0) state.runs[index] = campaign;
  else state.runs.unshift(campaign);
  state.runs.sort((left, right) => right.createdAt.localeCompare(left.createdAt));
  state.selectedRunId = campaign.campaignId;
}

function applyEvent(event) {
  const sequence = Number(event.lastEventId || 0);
  const parsed = parseSsePayload(event.data);
  if (!parsed.valid) {
    setAppError("SSE event 형식을 읽을 수 없습니다. snapshot으로 복구합니다.");
    void refreshSnapshot(state.liveCampaign?.campaignId || state.selectedRunId, true);
    return;
  }
  const sequenceDecision = snapshots.acceptEvent(state.lastSequence, sequence);
  if (!sequenceDecision.accept) return;
  state.lastSequence = sequenceDecision.sequence;
  const payload = parsed.payload;
  if (payload.target) state.phaseByTarget.set(payload.target, payload.phase || "console");
  if (payload.line) {
    state.consoleLines.push(`[${payload.target || "campaign"}] phase=${payload.phase} ${payload.line}`);
    state.consoleLines = state.consoleLines.slice(-MAX_CONSOLE_LINES);
  }
  if (state.liveCampaign) {
    let targets = state.liveCampaign.targets;
    if (payload.target) {
      targets = targets.map((target) => target.key === payload.target ? { ...target, status: payload.status } : target);
    }
    const updatesCampaign = !payload.target || payload.phase === "campaign" || payload.phase === "cancel" || payload.status === "RUNNING" || payload.status === "CANCELLING";
    const updated = { ...state.liveCampaign, targets, status: updatesCampaign ? payload.status : state.liveCampaign.status };
    upsertRun(updated);
    if (TERMINAL_STATUSES.has(updated.status)) {
      clearApproval("terminal");
      state.liveCampaign = null;
      state.cancelSent = false;
      closeEvents();
      renderAll();
    } else {
      state.liveCampaign = updated;
      renderLive();
      renderConfiguration();
    }
  }
  if (payload.phase === "finish" || payload.phase === "campaign") {
    const campaignId = state.liveCampaign?.campaignId || state.selectedRunId;
    void refreshSnapshot(campaignId);
  }
}

function closeEvents() {
  if (state.eventSource) state.eventSource.close();
  state.eventSource = null;
  if (state.reconnectTimer) window.clearTimeout(state.reconnectTimer);
  state.reconnectTimer = null;
}

async function refreshSnapshot(campaignId, retainPending = false) {
  if (!campaignId) return null;
  const result = await (retainPending ? snapshots.recover(campaignId) : snapshots.refresh(campaignId));
  if (result.kind === "stale") return null;
  if (result.kind === "error") {
    setAppError(requestMessage(result.error, "Campaign snapshot을 갱신하지 못했습니다."));
    return null;
  }
  const campaign = result.campaign;
  upsertRun(campaign);
  if (isActive(campaign)) {
    state.liveCampaign = campaign;
  } else {
    clearApproval("terminal");
    if (state.liveCampaign?.campaignId === campaign.campaignId) state.liveCampaign = null;
    state.cancelSent = false;
    closeEvents();
  }
  renderAll();
  return campaign;
}

async function scheduleReconnect(campaignId) {
  if (!campaignId || !state.liveCampaign) return;
  const initialDecision = reconnectDecision({
    status: state.liveCampaign.status,
    timerPending: Boolean(state.reconnectTimer) || state.reconnectPending,
    delay: state.reconnectDelay,
  });
  if (!initialDecision.schedule) return;
  state.reconnectPending = true;
  if (state.eventSource) state.eventSource.close();
  state.eventSource = null;
  dom["live-status"].textContent = `SSE 연결이 끊겼습니다. ${initialDecision.delay / 1000}초 뒤 snapshot을 확인하고 다시 연결합니다.`;
  const snapshot = await refreshSnapshot(campaignId);
  state.reconnectPending = false;
  if (snapshot && TERMINAL_STATUSES.has(snapshot.status)) return;
  const decision = reconnectDecision({
    status: state.liveCampaign?.status,
    timerPending: Boolean(state.reconnectTimer),
    delay: state.reconnectDelay,
  });
  if (!decision.schedule) return;
  state.reconnectDelay = decision.nextDelay;
  state.reconnectTimer = window.setTimeout(() => {
    state.reconnectTimer = null;
    connectEvents(campaignId);
  }, decision.delay);
}

function connectEvents(campaignId) {
  closeEvents();
  if (!campaignId || !isActive(state.liveCampaign)) return;
  scopeStream(campaignId);
  const source = new EventSource(`/api/runs/${encodeURIComponent(campaignId)}/events`);
  state.eventSource = source;
  source.addEventListener("open", () => {
    if (state.streamCampaignId !== campaignId) return;
    state.reconnectDelay = 1000;
    state.reconnectPending = false;
    setAppError("");
    if (state.liveCampaign) dom["live-status"].textContent = `${state.liveCampaign.status} SSE 연결됨. campaign ${campaignId}`;
  });
  source.addEventListener("campaign", (event) => {
    if (state.streamCampaignId !== campaignId) return;
    applyEvent(event);
  });
  source.addEventListener("error", () => {
    if (state.streamCampaignId !== campaignId) return;
    void scheduleReconnect(campaignId);
  });
}

async function startCampaign(mode) {
  setAppError("");
  const selected = selectedTargets();
  const signature = currentRiskSignature(selected);
  const jwtSecret = dom["jwt-secret"].value;
  const payload = buildRunPayload(mode === "safe-all" ? { mode, jwtSecret } : {
    mode,
    jwtSecret,
    selectedKeys: selected.map((target) => target.key),
    profile: dom.profile.value,
    load: Number(dom["load-value"].value),
    extent: dom["extent-value"].value.trim(),
    riskApproved: Boolean(signature && dom["risk-approval"].checked && state.approvedRiskSignature === signature),
    jfrEnabled: dom["jfr-enabled"].checked,
  });
  dom["safe-all-button"].disabled = true;
  dom["selected-run-button"].disabled = true;
  dom["live-status"].textContent = "실행 요청을 보내는 중입니다.";
  try {
    const campaign = await fetchJson("/api/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    state.cancelSent = false;
    scopeStream(campaign.campaignId);
    upsertRun(campaign);
    state.liveCampaign = campaign;
    renderAll();
    dom["live-title"].focus({ preventScroll: true });
    connectEvents(campaign.campaignId);
  } catch (error) {
    setAppError(requestMessage(error, "실행 요청에 실패했습니다."));
    if (error instanceof RequestError && error.status === 409) await reloadRuns();
    renderConfiguration();
  }
}

async function cancelCampaign() {
  const campaign = state.liveCampaign;
  if (!canCancel({ campaign, cancelSent: state.cancelSent })) return;
  state.cancelSent = true;
  renderConfiguration();
  dom["live-status"].textContent = "취소 요청을 보내는 중입니다. cleanup 완료까지 기다립니다.";
  try {
    await fetchJson(`/api/runs/${encodeURIComponent(campaign.campaignId)}/cancel`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    const cancelling = { ...campaign, status: "CANCELLING" };
    state.liveCampaign = cancelling;
    upsertRun(cancelling);
    renderAll();
  } catch (error) {
    setAppError(requestMessage(error, "취소 요청에 실패했습니다."));
    if (!(error instanceof RequestError && error.status === 409)) state.cancelSent = false;
    await refreshSnapshot(campaign.campaignId);
  }
}

async function reloadRuns() {
  try {
    const documentBody = await fetchJson("/api/runs");
    state.runs = documentBody.runs || [];
    const active = selectActiveCampaign(state.runs);
    state.liveCampaign = active;
    if (active) {
      state.selectedRunId = active.campaignId;
      connectEvents(active.campaignId);
    } else {
      clearApproval("terminal");
      scopeStream("");
      closeEvents();
    }
    renderAll();
  } catch (error) {
    setAppError(requestMessage(error, "실행 기록을 다시 불러오지 못했습니다."));
  }
}

function scopeStream(campaignId) {
  const decision = streamScopeDecision(state.streamCampaignId, campaignId);
  if (decision.reset) {
    snapshots.abort();
    state.consoleLines = [];
    state.phaseByTarget.clear();
    state.lastSequence = 0;
  }
  state.streamCampaignId = decision.campaignId;
}

function bindControls() {
  for (const id of ["target-search", "suite-filter", "risk-filter"]) dom[id].addEventListener(id === "target-search" ? "input" : "change", renderCatalog);
  dom["safe-select-button"].addEventListener("click", () => {
    clearApproval("selection");
    state.selectedKeys = new Set(selectionForAction({ targets: state.targets, profile: dom.profile.value, action: "safe-defaults" }));
    renderCatalog();
    renderConfiguration();
  });
  dom["clear-selection-button"].addEventListener("click", () => {
    clearApproval("selection");
    state.selectedKeys = new Set(selectionForAction({ targets: state.targets, profile: dom.profile.value, action: "clear" }));
    renderCatalog();
    renderConfiguration();
  });
  dom.profile.addEventListener("change", () => {
    clearApproval("profile");
    const rule = profileRule(dom.profile.value);
    dom["load-value"].value = "1";
    dom["extent-value"].value = rule.extent;
    renderConfiguration();
  });
  dom["load-value"].addEventListener("input", () => {
    clearApproval("load");
    renderConfiguration();
  });
  dom["extent-value"].addEventListener("input", () => {
    clearApproval("extent");
    renderConfiguration();
  });
  dom["risk-approval"].addEventListener("change", () => {
    state.approvedRiskSignature = transitionApproval({
      approvedSignature: state.approvedRiskSignature,
      action: "acknowledge",
      currentSignature: currentRiskSignature(),
      checked: dom["risk-approval"].checked,
    });
    renderConfiguration();
  });
  dom["jfr-enabled"].addEventListener("change", renderConfiguration);
  dom["safe-all-button"].addEventListener("click", () => { void startCampaign("safe-all"); });
  dom["selected-run-button"].addEventListener("click", () => { void startCampaign("selected"); });
  dom["cancel-button"].addEventListener("click", () => { void cancelCampaign(); });
  dom["history-select"].addEventListener("change", () => {
    state.selectedRunId = dom["history-select"].value;
    renderResults();
  });
}

async function initialize() {
  bindControls();
  try {
    const [targetsDocument, runsDocument] = await Promise.all([fetchJson("/api/targets"), fetchJson("/api/runs")]);
    state.targets = targetsDocument.targets || [];
    state.runs = runsDocument.runs || [];
    state.selectedKeys = new Set(selectionForAction({ targets: state.targets, profile: "smoke", action: "safe-defaults" }));
    const active = selectActiveCampaign(state.runs);
    state.liveCampaign = active;
    state.selectedRunId = state.runs[0]?.campaignId || "";
    state.loading = false;
    renderAll();
    if (active) {
      dom["live-status"].textContent = "활성 campaign snapshot을 복구했습니다. SSE에 다시 연결합니다.";
      connectEvents(active.campaignId);
    }
  } catch (error) {
    state.loading = false;
    setAppError(requestMessage(error, "초기 데이터를 불러오지 못했습니다."));
    renderAll();
  }
}

window.setInterval(() => {
  if (state.liveCampaign) dom["elapsed-time"].textContent = formatElapsed(state.liveCampaign);
}, 1000);
window.addEventListener("beforeunload", closeEvents);
void initialize();
