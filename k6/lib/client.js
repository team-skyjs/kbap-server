import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const endpointFailures = new Counter('endpoint_failures');

function requestTags(context, endpoint, tags) {
  return {
    run_id: context.runId,
    target: endpoint.key,
    route: endpoint.route,
    method: endpoint.method,
    phase: context.phase,
    ...tags,
  };
}

function failureCode(response) {
  try {
    const code = response.json('code');
    return code === null || code === undefined ? 'unknown' : String(code);
  } catch (_) {
    return 'unparseable';
  }
}

export function authenticatedParams(context, version, tags = {}) {
  return {
    headers: {
      'X-API-Version': version,
      Authorization: `Bearer ${context.accessToken}`,
      'Content-Type': 'application/json',
    },
    tags,
  };
}

export function executeEndpoint(endpoint, context) {
  const request = endpoint.request(context);
  const params = {
    ...request.params,
    tags: requestTags(context, endpoint, request.params.tags || {}),
  };
  const response = http.request(endpoint.method, request.url, request.body, params);
  const ok = check(response, {
    [`${endpoint.key} status 200`]: (r) => r.status === 200,
    [`${endpoint.key} success true`]: (r) => {
      try {
        return r.json('success') === true;
      } catch (_) {
        return false;
      }
    },
  });

  if (!ok) {
    endpointFailures.add(1, { business_code: failureCode(response) });
  }

  return response;
}
