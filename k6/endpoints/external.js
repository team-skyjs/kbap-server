function encode(value) {
  return encodeURIComponent(String(value));
}

function externalEndpoint(definition) {
  return {
    key: definition.key,
    method: definition.method,
    route: definition.route,
    kind: 'external',
    requiresAuth: true,
    fixtureKeys: definition.fixtureKeys || [],
    request: definition.request,
    execute: definition.execute,
  };
}

function request(context, url, body, version = '1.0', extra = {}) {
  const params = context.authenticatedParams(version);
  return {
    url: `${context.baseUrl}${url}`,
    body: body === null ? null : JSON.stringify(body),
    params: {
      ...params,
      ...extra,
      headers: { ...params.headers, ...(extra.headers || {}) },
    },
  };
}

function executeScanV2(endpoint, context, currency) {
  const ticketEndpoint = { ...endpoint, route: '/api/scans/tickets' };
  const ticketResponse = context.executeRequest(
    ticketEndpoint,
    context,
    request(context, '/api/scans/tickets', null),
  );
  let ticket;
  try {
    ticket = ticketResponse.status === 200 ? ticketResponse.json('payload.ticket') : null;
  } catch (_) {
    ticket = null;
  }
  if (!ticket) {
    context.recordScanFailed('ticket');
    return;
  }
  context.executeRequest(
    endpoint,
    context,
    request(
      context,
      `/api/scans?lang=ko&currency=${currency}`,
      { imagePath: context.fixtures.scanImagePath },
      '2.0',
      { headers: { 'X-Scan-Ticket': ticket }, timeout: context.scanTimeout },
    ),
  );
}

function scanV2Endpoint(key, currency) {
  const endpoint = externalEndpoint({
    key,
    method: 'POST',
    route: '/api/scans',
    fixtureKeys: ['scanImagePath'],
  });
  endpoint.execute = (context) => executeScanV2(endpoint, context, currency);
  return endpoint;
}

export const externalEndpoints = [
  externalEndpoint({
    key: 'place-nearby', method: 'GET', route: '/api/places/nearby',
    fixtureKeys: ['placeLatitude', 'placeLongitude'],
    request: (context) => request(
      context,
      `/api/places/nearby?latitude=${encode(context.fixtures.placeLatitude)}`
        + `&longitude=${encode(context.fixtures.placeLongitude)}&lang=ko`,
      null,
    ),
  }),
  externalEndpoint({
    key: 'place-search', method: 'GET', route: '/api/places/search',
    fixtureKeys: ['placeLatitude', 'placeLongitude', 'placeQuery'],
    request: (context) => request(
      context,
      `/api/places/search?query=${encode(context.fixtures.placeQuery)}`
        + `&latitude=${encode(context.fixtures.placeLatitude)}`
        + `&longitude=${encode(context.fixtures.placeLongitude)}&lang=ko`,
      null,
    ),
  }),
  externalEndpoint({
    key: 'scan-ticket', method: 'POST', route: '/api/scans/tickets',
    request: (context) => request(context, '/api/scans/tickets', null),
  }),
  externalEndpoint({
    key: 'scan-v1', method: 'POST', route: '/api/scans', fixtureKeys: ['scanImagePath'],
    request: (context) => request(context, '/api/scans?lang=ko', {
      imagePath: context.fixtures.scanImagePath,
      items: [{ idx: 0, rawMenuName: '김치찌개' }],
    }, '1.0', { timeout: context.scanTimeout }),
  }),
  scanV2Endpoint('scan-v2-krw', 'KRW'),
  scanV2Endpoint('scan-v2-usd', 'USD'),
];
