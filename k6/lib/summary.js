const trendKeys = ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function metric(data, name) {
  return data.metrics[name] || {};
}

function display(value) {
  return value === undefined || value === null ? '-' : String(value);
}

function row(label, value) {
  return `<tr><th>${escapeHtml(label)}</th><td>${escapeHtml(display(value))}</td></tr>`;
}

function trendRows(data) {
  return Object.entries(data.metrics)
    .filter(([, values]) => trendKeys.some((key) => values[key] !== undefined))
    .map(([name, values]) => `<tr><th>${escapeHtml(name)}</th>${trendKeys
      .map((key) => `<td>${escapeHtml(display(values[key]))}</td>`)
      .join('')}</tr>`)
    .join('');
}

export function summaryMetadata(config) {
  return {
    target: config.target,
    runId: config.runId,
    startedAt: config.startedAt,
  };
}

export function renderHtmlSummary(data, metadata) {
  const checks = metric(data, 'checks');
  const requests = metric(data, 'http_reqs');
  const failed = metric(data, 'http_req_failed');
  const dropped = metric(data, 'dropped_iterations');
  const completedMetadata = metadata;

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>k6 summary: ${escapeHtml(completedMetadata.target)}</title>
<style>body{font-family:system-ui,sans-serif;margin:2rem;color:#172033;background:#f7f9fc}main{max-width:72rem;margin:auto;background:#fff;padding:2rem;border-radius:.75rem;box-shadow:0 .2rem 1rem #17203318}table{border-collapse:collapse;width:100%;margin:1rem 0 2rem}th,td{border:1px solid #d5dce8;padding:.6rem;text-align:left;overflow-wrap:anywhere}th{background:#edf2f8}h1,h2{color:#12315a}</style>
</head>
<body><main>
<h1>k6 performance summary</h1>
<table><tbody>${row('Target', completedMetadata.target)}${row('Run ID', completedMetadata.runId)}${row('Started at', completedMetadata.startedAt)}${row('Ended at', completedMetadata.endedAt)}${row('Checks passed', checks.passes)}${row('Checks failed', checks.fails)}${row('Request count', requests.count)}${row('Failed rate', failed.value)}${row('Dropped iterations', dropped.count)}</tbody></table>
<h2>Trend metrics</h2>
<table><thead><tr><th>Metric</th>${trendKeys.map((key) => `<th>${escapeHtml(key)}</th>`).join('')}</tr></thead><tbody>${trendRows(data)}</tbody></table>
</main></body>
</html>`;
}

export function renderJsonSummary(data, metadata) {
  return JSON.stringify({ metadata, data }, null, 2);
}
