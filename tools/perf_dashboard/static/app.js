"use strict";

const ACTIVE_STATUSES = new Set(["QUEUED", "RUNNING", "CANCELLING"]);
const TERMINAL_STATUSES = new Set(["PASSED", "FAILED", "CANCELLED"]);
const PROFILE_RULES = {
  smoke: { loadLabel: "동시 부하", loadHelp: "smoke 부하는 1로 고정됩니다.", maxLoad: 1, extentLabel: "반복 횟수", extentHelp: "smoke 반복 횟수는 1입니다.", extent: "1" },
  read: { loadLabel: "초당 요청 수", loadHelp: "read 요청률 상한은 초당 40회입니다.", maxLoad: 40, extentLabel: "지속 시간", extentHelp: "1s부터 300s 또는 5m까지 입력합니다.", extent: "30s" },
  write: { loadLabel: "가상 사용자 수", loadHelp: "write VU 상한은 10입니다.", maxLoad: 10, extentLabel: "지속 시간", extentHelp: "1s부터 120s 또는 2m까지 입력합니다.", extent: "30s" },
  external: { loadLabel: "가상 사용자 수", loadHelp: "external VU 상한은 10입니다.", maxLoad: 10, extentLabel: "반복 횟수", extentHelp: "비용 보호를 위해 1회부터 10회까지만 허용됩니다.", extent: "1" },
};
const PROFILE_COPY = {
  smoke: "smoke는 각 target을 한 번 실행해 연결, fixture, JFR 계약을 검증합니다.",
  read: "read는 요청률과 지속 시간으로 읽기 endpoint 처리량을 측정합니다.",
  write: "write는 제한된 VU와 지속 시간으로 쓰기 endpoint를 측정합니다.",
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
  loading: true,
};

const dom = Object.fromEntries([
  "header-status", "app-error", "selection-count", "safe-select-button", "target-search", "suite-filter", "risk-filter", "target-list",
  "profile", "profile-help", "load-label", "load-value", "load-help", "extent-label", "extent-value", "extent-help", "jfr-enabled",
  "risk-approval-row", "risk-approval", "risk-warning", "configuration-error", "safe-all-button", "selected-run-button", "cancel-button",
  "live-title", "live-status", "elapsed-time", "active-summary", "active-targets", "console-output", "history-select", "results-body",
  "artifact-state", "bundle-download", "artifact-list",
].map((id) => [id, document.getElementById(id)]));

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
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = state.selectedKeys.has(target.key);
    input.disabled = locked;
    input.dataset.targetKey = target.key;
    input.addEventListener("change", () => {
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

function compatibleWithProfile(targets, profile) {
  return profile === "smoke" || targets.every((target) => target.defaultProfile === profile);
}

function parseDurationSeconds(raw) {
  const match = /^([1-9][0-9]*)([sm])$/.exec(raw);
  if (!match) return null;
  return Number(match[1]) * (match[2] === "m" ? 60 : 1);
}

function configurationIssue() {
  const targets = selectedTargets();
  const profile = dom.profile.value;
  const rule = PROFILE_RULES[profile];
  const load = Number(dom["load-value"].value);
  const extent = dom["extent-value"].value.trim();
  if (!targets.length) return "실행할 target을 하나 이상 선택하세요.";
  if (!compatibleWithProfile(targets, profile)) return ERROR_COPY["profile-target-mismatch"];
  if (!Number.isInteger(load) || load < 1 || load > rule.maxLoad) return `부하 값은 1부터 ${rule.maxLoad}까지 입력하세요.`;
  if (profile === "smoke" && extent !== "1") return "smoke 반복 횟수는 1이어야 합니다.";
  if (profile === "external" && (!/^[1-9][0-9]*$/.test(extent) || Number(extent) > 10)) return "external 반복 횟수는 1부터 10까지 입력하세요.";
  if ((profile === "read" || profile === "write") && parseDurationSeconds(extent) === null) return "지속 시간은 1s 또는 1m 형식으로 입력하세요.";
  if (profile === "read" && parseDurationSeconds(extent) > 300) return "read 지속 시간은 최대 300초입니다.";
  if (profile === "write" && parseDurationSeconds(extent) > 120) return "write 지속 시간은 최대 120초입니다.";
  const risky = targets.some((target) => target.risk !== "safe");
  if (risky && !dom["risk-approval"].checked) return ERROR_COPY["risk-approval-required"];
  if (!dom["jfr-enabled"].checked && (profile !== "smoke" || targets.length !== 1)) return ERROR_COPY["jfr-off-requires-single-smoke"];
  return "";
}

function maxCalls(targets) {
  const profile = dom.profile.value;
  const load = Number(dom["load-value"].value) || 0;
  const extent = dom["extent-value"].value.trim();
  const extentValue = profile === "read" || profile === "write" ? (parseDurationSeconds(extent) || 0) : (Number(extent) || 0);
  return targets.length * load * extentValue;
}

function renderConfiguration() {
  const locked = isActive(state.liveCampaign);
  const profile = dom.profile.value;
  const rule = PROFILE_RULES[profile];
  const targets = selectedTargets();
  const risky = targets.filter((target) => target.risk !== "safe");
  dom["load-label"].textContent = rule.loadLabel;
  dom["load-help"].textContent = rule.loadHelp;
  dom["extent-label"].textContent = rule.extentLabel;
  dom["extent-help"].textContent = rule.extentHelp;
  dom["profile-help"].textContent = PROFILE_COPY[profile];
  dom["load-value"].max = String(rule.maxLoad);
  dom["risk-approval-row"].hidden = risky.length === 0;
  dom["risk-warning"].hidden = risky.length === 0;
  if (risky.length) {
    const fixtureCount = risky.filter((target) => target.risk === "fixture").length;
    const costCount = risky.filter((target) => target.risk === "cost").length;
    dom["risk-warning"].textContent = `위험 선택: fixture ${fixtureCount}개, cost ${costCount}개. 예상 최대 호출 수 ${maxCalls(targets)}회. 실제 호출 수는 runner 단계와 취소 시점에 따라 더 적을 수 있습니다.`;
  }
  const jfrCanChange = !locked && profile === "smoke" && targets.length === 1;
  dom["jfr-enabled"].disabled = !jfrCanChange;
  if (!jfrCanChange) dom["jfr-enabled"].checked = true;
  const issue = locked ? "실행 중에는 target과 설정을 변경할 수 없습니다." : configurationIssue();
  dom["configuration-error"].textContent = issue;
  dom["selected-run-button"].disabled = locked || Boolean(issue);
  dom["safe-all-button"].disabled = locked || state.loading || !state.targets.some((target) => target.defaultEnabled && target.risk === "safe");
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
  if (current === null || current === undefined) return "데이터 없음";
  const formatted = `${(current * scale).toFixed(scale === 100 ? 2 : 1)}${unit}`;
  if (previous === null || previous === undefined || previous === 0) return `${formatted} | 비교 기준 없음`;
  const absolute = (current - previous) * scale;
  const percent = ((current - previous) / previous) * 100;
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

function tableCell(label, text, className = "") {
  const cell = element("td", className);
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
    const name = tableCell("Target", targetMeta?.label || target.key, "target-cell");
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
    const title = element("h4", "", target.key);
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
    for (const artifact of jfrs) links.append(artifactLink(campaign, artifact, artifact.name));
    for (let index = jfrs.length; index < 2; index += 1) links.append(element("span", "download-unavailable", `task JFR ${index + 1} ${terminal ? "없음" : "수집 중"}`));
    const complete = expected.every(([name]) => byName.has(name)) && jfrs.length >= 2;
    if (terminal && !complete) partialCount += 1;
    group.append(title, element("p", complete ? "help" : "error-text", complete ? "필수 artifact 5개 준비됨" : terminal ? "부분 수집 상태" : "수집 중"), links);
    return group;
  });
  dom["artifact-state"].textContent = partialCount ? `${partialCount}개 target이 부분 수집 상태입니다. manifest와 두 task JFR을 함께 확인하세요.` : terminal ? "수집된 artifact는 localhost allowlist route로만 제공됩니다." : "실행 중이며 artifact 수집 상태를 갱신합니다.";
  replaceChildren(dom["artifact-list"], groups);
}

function renderAll() {
  renderCatalog();
  renderConfiguration();
  renderLive();
  renderHistoryPicker();
  renderResults();
}

function updateRun(campaign) {
  const index = state.runs.findIndex((run) => run.campaignId === campaign.campaignId);
  if (index >= 0) state.runs[index] = campaign;
  else state.runs.unshift(campaign);
  state.runs.sort((left, right) => right.createdAt.localeCompare(left.createdAt));
  state.liveCampaign = campaign;
  state.selectedRunId = campaign.campaignId;
}

function applyEvent(event) {
  const sequence = Number(event.lastEventId || 0);
  if (sequence && sequence <= state.lastSequence) return;
  if (sequence) state.lastSequence = sequence;
  let payload;
  try {
    payload = JSON.parse(event.data);
  } catch (error) {
    if (error instanceof SyntaxError) setAppError("SSE event 형식을 읽을 수 없습니다. snapshot으로 복구합니다.");
    return;
  }
  if (payload.target) state.phaseByTarget.set(payload.target, payload.phase || "console");
  if (payload.line) {
    state.consoleLines.push(`[${payload.target || "campaign"}] phase=${payload.phase} ${payload.line}`);
    state.consoleLines = state.consoleLines.slice(-MAX_CONSOLE_LINES);
  }
  if (state.liveCampaign) {
    if (payload.target) {
      state.liveCampaign.targets = state.liveCampaign.targets.map((target) => target.key === payload.target ? { ...target, status: payload.status } : target);
    }
    if (!payload.target || payload.phase === "campaign" || payload.phase === "cancel" || payload.status === "RUNNING" || payload.status === "CANCELLING") state.liveCampaign.status = payload.status;
  }
  renderLive();
  renderConfiguration();
  if (payload.phase === "finish" || payload.phase === "campaign") void refreshSnapshot(state.liveCampaign?.campaignId);
}

function closeEvents() {
  if (state.eventSource) state.eventSource.close();
  state.eventSource = null;
  if (state.reconnectTimer) window.clearTimeout(state.reconnectTimer);
  state.reconnectTimer = null;
}

async function refreshSnapshot(campaignId) {
  if (!campaignId) return null;
  try {
    const campaign = await fetchJson(`/api/runs/${encodeURIComponent(campaignId)}`);
    updateRun(campaign);
    renderAll();
    if (TERMINAL_STATUSES.has(campaign.status)) {
      closeEvents();
      state.cancelSent = false;
    }
    return campaign;
  } catch (error) {
    setAppError(requestMessage(error, "Campaign snapshot을 갱신하지 못했습니다."));
    return null;
  }
}

async function scheduleReconnect(campaignId) {
  if (!campaignId || state.reconnectTimer || !isActive(state.liveCampaign)) return;
  if (state.eventSource) state.eventSource.close();
  state.eventSource = null;
  dom["live-status"].textContent = `SSE 연결이 끊겼습니다. ${state.reconnectDelay / 1000}초 뒤 snapshot을 확인하고 다시 연결합니다.`;
  const snapshot = await refreshSnapshot(campaignId);
  if (snapshot && TERMINAL_STATUSES.has(snapshot.status)) return;
  const delay = state.reconnectDelay;
  state.reconnectDelay = Math.min(state.reconnectDelay * 2, 10000);
  state.reconnectTimer = window.setTimeout(() => {
    state.reconnectTimer = null;
    connectEvents(campaignId);
  }, delay);
}

function connectEvents(campaignId) {
  closeEvents();
  if (!campaignId || !isActive(state.liveCampaign)) return;
  const source = new EventSource(`/api/runs/${encodeURIComponent(campaignId)}/events`);
  state.eventSource = source;
  source.addEventListener("open", () => {
    state.reconnectDelay = 1000;
    setAppError("");
    if (state.liveCampaign) dom["live-status"].textContent = `${state.liveCampaign.status} SSE 연결됨. campaign ${campaignId}`;
  });
  source.addEventListener("campaign", applyEvent);
  source.addEventListener("error", () => { void scheduleReconnect(campaignId); });
}

async function startCampaign(mode) {
  setAppError("");
  const selected = selectedTargets();
  const safeAll = mode === "safe-all";
  const payload = safeAll ? {
    mode: "safe-all",
    profile: "smoke",
    rateOrVus: 1,
    durationOrIterations: "1",
    jfrEnabled: true,
  } : {
    mode: "selected",
    targetKeys: selected.map((target) => target.key),
    profile: dom.profile.value,
    rateOrVus: Number(dom["load-value"].value),
    durationOrIterations: dom["extent-value"].value.trim(),
    allowRisk: dom["risk-approval"].checked,
    jfrEnabled: dom["jfr-enabled"].checked,
  };
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
    state.consoleLines = [];
    state.phaseByTarget.clear();
    state.lastSequence = 0;
    updateRun(campaign);
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
  if (!campaign || state.cancelSent || !isActive(campaign)) return;
  state.cancelSent = true;
  renderConfiguration();
  dom["live-status"].textContent = "취소 요청을 보내는 중입니다. cleanup 완료까지 기다립니다.";
  try {
    await fetchJson(`/api/runs/${encodeURIComponent(campaign.campaignId)}/cancel`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    campaign.status = "CANCELLING";
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
    const active = state.runs.find(isActive);
    if (active) {
      state.liveCampaign = active;
      state.selectedRunId = active.campaignId;
      connectEvents(active.campaignId);
    }
    renderAll();
  } catch (error) {
    setAppError(requestMessage(error, "실행 기록을 다시 불러오지 못했습니다."));
  }
}

function bindControls() {
  for (const id of ["target-search", "suite-filter", "risk-filter"]) dom[id].addEventListener(id === "target-search" ? "input" : "change", renderCatalog);
  dom["safe-select-button"].addEventListener("click", () => {
    const profile = dom.profile.value;
    state.selectedKeys = new Set(state.targets.filter((target) => target.defaultEnabled && target.risk === "safe" && (profile === "smoke" || target.defaultProfile === profile)).map((target) => target.key));
    renderCatalog();
    renderConfiguration();
  });
  dom.profile.addEventListener("change", () => {
    const rule = PROFILE_RULES[dom.profile.value];
    dom["load-value"].value = "1";
    dom["extent-value"].value = rule.extent;
    renderConfiguration();
  });
  for (const id of ["load-value", "extent-value"]) dom[id].addEventListener("input", renderConfiguration);
  for (const id of ["risk-approval", "jfr-enabled"]) dom[id].addEventListener("change", renderConfiguration);
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
    state.selectedKeys = new Set(state.targets.filter((target) => target.defaultEnabled && target.risk === "safe").map((target) => target.key));
    const active = state.runs.find(isActive);
    state.liveCampaign = active || state.runs[0] || null;
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
