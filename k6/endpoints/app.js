const version = '1.0';

function params(context, requiresAuth) {
  if (requiresAuth) {
    return context.authenticatedParams(version);
  }
  return { headers: { 'X-API-Version': version }, tags: {} };
}

function readEndpoint(definition) {
  return {
    key: definition.key,
    method: 'GET',
    route: definition.route,
    kind: 'read',
    requiresAuth: definition.requiresAuth || false,
    request(context) {
      return {
        url: `${context.baseUrl}${definition.path}`,
        body: null,
        params: params(context, definition.requiresAuth),
      };
    },
  };
}

export const appEndpoints = [
  readEndpoint({ key: 'app-version', route: '/api/app-version', path: '/api/app-version' }),
  readEndpoint({ key: 'home-auth', route: '/api/home', path: '/api/home?lang=ko', requiresAuth: true }),
  readEndpoint({ key: 'home-guest', route: '/api/home', path: '/api/home?lang=ko' }),
  readEndpoint({ key: 'ingredients-ko', route: '/api/ingredients', path: '/api/ingredients?lang=ko' }),
  readEndpoint({ key: 'ingredients-en', route: '/api/ingredients', path: '/api/ingredients?lang=en' }),
  readEndpoint({ key: 'ingredient-diets-ko', route: '/api/ingredients/diets', path: '/api/ingredients/diets?lang=ko' }),
  readEndpoint({ key: 'ingredient-diets-en', route: '/api/ingredients/diets', path: '/api/ingredients/diets?lang=en' }),
];
