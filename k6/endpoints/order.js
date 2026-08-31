const version = '1.0';

function encode(value) {
  return encodeURIComponent(String(value));
}

function orderEndpoint(definition) {
  return {
    key: definition.key,
    method: 'GET',
    route: definition.route,
    kind: 'read',
    requiresAuth: true,
    fixtureKeys: definition.fixtureKeys || [],
    request(context) {
      return {
        url: `${context.baseUrl}${definition.path(context.fixtures)}`,
        body: null,
        params: context.authenticatedParams(version),
      };
    },
  };
}

export const orderEndpoints = [
  orderEndpoint({ key: 'orders-10', route: '/api/orders', path: () => '/api/orders?size=10' }),
  orderEndpoint({ key: 'orders-30', route: '/api/orders', path: () => '/api/orders?size=30' }),
  orderEndpoint({
    key: 'orders-next',
    route: '/api/orders',
    fixtureKeys: ['orderId'],
    path: (fixtures) => `/api/orders?size=10&cursor=${encode(fixtures.orderId)}`,
  }),
  orderEndpoint({
    key: 'order-detail',
    route: '/api/orders/{orderId}',
    fixtureKeys: ['orderId'],
    path: (fixtures) => `/api/orders/${encode(fixtures.orderId)}`,
  }),
];
