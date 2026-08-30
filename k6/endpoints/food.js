const version = '1.0';
const missingKeyword = '__kbap_load_test_missing__';

function params(context, requiresAuth) {
  if (requiresAuth) {
    return context.authenticatedParams(version);
  }
  return { headers: { 'X-API-Version': version }, tags: {} };
}

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
      return {
        url: `${context.baseUrl}${definition.path(context.fixtures)}`,
        body: null,
        params: params(context, definition.requiresAuth),
      };
    },
  };
}

const browse = (fixtures) => `/api/foods?lang=ko&cursor=${encode(fixtures.foodCursor)}`;
const detail = (fixtures) => `/api/foods/${encode(fixtures.foodId)}?lang=ko`;
const bookmarkPage = (fixtures) => `/api/bookmarks?lang=ko&cursor=${encode(fixtures.bookmarkCursor)}`;

export const foodEndpoints = [
  readEndpoint({ key: 'foods-auth', route: '/api/foods', requiresAuth: true, path: () => '/api/foods?lang=ko' }),
  readEndpoint({ key: 'foods-guest', route: '/api/foods', path: () => '/api/foods?lang=ko' }),
  readEndpoint({ key: 'foods-next', route: '/api/foods', requiresAuth: true, fixtureKeys: ['foodCursor'], path: browse }),
  readEndpoint({
    key: 'foods-search-all-ko-hit',
    route: '/api/foods/search',
    requiresAuth: true,
    fixtureKeys: ['foodKeyword'],
    path: (fixtures) => `/api/foods/search?scope=all&lang=ko&keyword=${encode(fixtures.foodKeyword)}`,
  }),
  readEndpoint({
    key: 'foods-search-all-ko-miss',
    route: '/api/foods/search',
    requiresAuth: true,
    path: () => `/api/foods/search?scope=all&lang=ko&keyword=${encode(missingKeyword)}`,
  }),
  readEndpoint({
    key: 'foods-search-all-en-hit',
    route: '/api/foods/search',
    requiresAuth: true,
    fixtureKeys: ['foodKeyword'],
    path: (fixtures) => `/api/foods/search?scope=all&lang=en&keyword=${encode(fixtures.foodKeyword)}`,
  }),
  readEndpoint({
    key: 'foods-search-scanned',
    route: '/api/foods/search',
    requiresAuth: true,
    fixtureKeys: ['foodKeyword'],
    path: (fixtures) => `/api/foods/search?scope=scanned&lang=ko&keyword=${encode(fixtures.foodKeyword)}`,
  }),
  readEndpoint({
    key: 'foods-search-next',
    route: '/api/foods/search',
    requiresAuth: true,
    fixtureKeys: ['foodKeyword', 'foodCursor'],
    path: (fixtures) =>
      `/api/foods/search?scope=all&lang=ko&keyword=${encode(fixtures.foodKeyword)}&cursor=${encode(fixtures.foodCursor)}`,
  }),
  readEndpoint({ key: 'foods-scanned', route: '/api/foods/scanned', requiresAuth: true, path: () => '/api/foods/scanned?lang=ko' }),
  readEndpoint({
    key: 'foods-scanned-next',
    route: '/api/foods/scanned',
    requiresAuth: true,
    fixtureKeys: ['scanCursor'],
    path: (fixtures) => `/api/foods/scanned?lang=ko&cursor=${encode(fixtures.scanCursor)}`,
  }),
  readEndpoint({ key: 'food-detail-auth', route: '/api/foods/{foodId}', requiresAuth: true, fixtureKeys: ['foodId'], path: detail }),
  readEndpoint({ key: 'food-detail-guest', route: '/api/foods/{foodId}', fixtureKeys: ['foodId'], path: detail }),
  readEndpoint({ key: 'bookmarks', route: '/api/bookmarks', requiresAuth: true, path: () => '/api/bookmarks?lang=ko' }),
  readEndpoint({
    key: 'bookmarks-next',
    route: '/api/bookmarks',
    requiresAuth: true,
    fixtureKeys: ['bookmarkCursor'],
    path: bookmarkPage,
  }),
];
