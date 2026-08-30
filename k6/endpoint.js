import { authenticatedParams, executeEndpoint } from './lib/client.js';
import { requireConfig } from './lib/config.js';
import { buildOptions } from './lib/options.js';
import { renderHtmlSummary, renderJsonSummary, summaryMetadata } from './lib/summary.js';
import { endpoints } from './endpoints/index.js';

const config = requireConfig(__ENV);
const endpoint = endpoints[config.target];

if (!endpoint) {
  throw new Error(`unknown TARGET: ${config.target}`);
}

if (endpoint.requiresAuth && !config.accessToken) {
  throw new Error('ACCESS_TOKEN is required for authenticated targets');
}

const context = {
  ...config,
  phase: __ENV.PHASE || 'measurement',
  authenticatedParams: (version, tags) => authenticatedParams(config, version, tags),
};

export const options = buildOptions(endpoint.kind, __ENV);

export default function () {
  executeEndpoint(endpoint, context);
}

export function handleSummary(data) {
  const metadata = { ...summaryMetadata(config), endedAt: new Date().toISOString() };
  return {
    [`${config.reportDir}/report.html`]: renderHtmlSummary(data, metadata),
    [`${config.reportDir}/summary.json`]: renderJsonSummary(data, metadata),
  };
}
