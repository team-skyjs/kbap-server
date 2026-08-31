const version = '1.0';

function memberEndpoint(key, route) {
  return {
    key,
    method: 'GET',
    route,
    kind: 'read',
    requiresAuth: true,
    request(context) {
      return {
        url: `${context.baseUrl}${route}`,
        body: null,
        params: context.authenticatedParams(version),
      };
    },
  };
}

export const memberEndpoints = [
  memberEndpoint('member-profile', '/api/members/me/profile'),
  memberEndpoint('member-ranking', '/api/members/me/ranking'),
  memberEndpoint('member-blocks', '/api/members/me/blocks'),
];
