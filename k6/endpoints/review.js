const version = '1.0';

function encode(value) {
  return encodeURIComponent(String(value));
}

function readEndpoint(definition) {
  return {
    key: definition.key,
    method: 'GET',
    route: definition.route,
    kind: 'read',
    requiresAuth: definition.requiresAuth || false,
    fixtureKeys: definition.fixtureKeys || [],
    request(context) {
      const params = definition.requiresAuth
        ? context.authenticatedParams(version)
        : { headers: { 'X-API-Version': version }, tags: {} };
      return {
        url: `${context.baseUrl}${definition.path(context.fixtures)}`,
        body: null,
        params,
      };
    },
  };
}

function listPath(sort) {
  return (fixtures) => `/api/reviews?foodId=${encode(fixtures.foodId)}&lang=ko&sort=${sort}`;
}

export const reviewEndpoints = [
  readEndpoint({
    key: 'reviews-guest-latest', route: '/api/reviews', fixtureKeys: ['foodId'], path: listPath('latest'),
  }),
  readEndpoint({
    key: 'reviews-auth-latest', route: '/api/reviews', requiresAuth: true, fixtureKeys: ['foodId'], path: listPath('latest'),
  }),
  readEndpoint({
    key: 'reviews-rating-high', route: '/api/reviews', requiresAuth: true, fixtureKeys: ['foodId'], path: listPath('rating_high'),
  }),
  readEndpoint({
    key: 'reviews-rating-low', route: '/api/reviews', requiresAuth: true, fixtureKeys: ['foodId'], path: listPath('rating_low'),
  }),
  readEndpoint({
    key: 'reviews-food-count', route: '/api/reviews', requiresAuth: true, fixtureKeys: ['foodId'], path: listPath('food_review_count'),
  }),
  readEndpoint({
    key: 'reviews-helpful', route: '/api/reviews', requiresAuth: true, fixtureKeys: ['foodId'], path: listPath('helpful'),
  }),
  readEndpoint({
    key: 'reviews-next',
    route: '/api/reviews',
    requiresAuth: true,
    fixtureKeys: ['foodId', 'reviewCursor'],
    path: (fixtures) =>
      `/api/reviews?foodId=${encode(fixtures.foodId)}&lang=ko&sort=latest&cursor=${encode(fixtures.reviewCursor)}`,
  }),
  readEndpoint({ key: 'reviews-me', route: '/api/reviews/me', requiresAuth: true, path: () => '/api/reviews/me?lang=ko' }),
  readEndpoint({
    key: 'reviews-me-next',
    route: '/api/reviews/me',
    requiresAuth: true,
    fixtureKeys: ['reviewCursor'],
    path: (fixtures) => `/api/reviews/me?lang=ko&cursor=${encode(fixtures.reviewCursor)}`,
  }),
];
