const route = '/api/app-version';

export const endpoint = {
  key: 'app-version',
  method: 'GET',
  route,
  kind: 'read',
  requiresAuth: false,
  request(context) {
    return {
      url: `${context.baseUrl}${route}`,
      body: null,
      params: {
        headers: { 'X-API-Version': '1.0' },
        tags: {},
      },
    };
  },
};
