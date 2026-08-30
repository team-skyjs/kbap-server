const version = '1.0';

function encode(value) {
  return encodeURIComponent(String(value));
}

function selectFixture(context, key) {
  const values = context.fixtures[key];
  if (!Array.isArray(values) || values.length === 0) {
    throw new Error(`fixture ${key} must be a non-empty array`);
  }
  return values[context.contended ? 0 : __VU % values.length];
}

function uniqueFixture(context, key) {
  const values = context.fixtures[key];
  const index = context.iterationInTest();
  if (!Array.isArray(values) || index >= values.length) {
    context.recordFixtureExhausted(key);
    return null;
  }
  return values[index];
}

function writeEndpoint(definition) {
  return {
    key: definition.key,
    method: definition.method,
    route: definition.route,
    kind: definition.kind || 'write',
    requiresAuth: true,
    fixtureKeys: definition.fixtureKeys || [],
    request(context) {
      return definition.request(context);
    },
  };
}

function request(context, url, body, apiVersion = version) {
  return {
    url: `${context.baseUrl}${url}`,
    body: body === null ? null : JSON.stringify(body),
    params: context.authenticatedParams(apiVersion),
  };
}

function reviewBody(context, action) {
  return {
    rating: 4,
    servingSpeed: 4,
    staffKindness: 4,
    content: `[load:${context.runId}] review ${action}`,
    imagePaths: [],
  };
}

function orderRequest(context, withLocation) {
  const fixture = uniqueFixture(context, 'orderFixtures');
  if (fixture === null) {
    return null;
  }
  const body = {
    imagePath: fixture.imagePath,
    items: [{
      foodId: fixture.foodId,
      menuName: `[load:${context.runId}] ${fixture.menuName}`,
      quantity: 1,
      price: fixture.price,
    }],
  };
  if (withLocation) {
    body.latitude = Number(context.fixtures.placeLatitude);
    body.longitude = Number(context.fixtures.placeLongitude);
  }
  return request(context, '/api/orders', body);
}

export const writeEndpoints = [
  writeEndpoint({
    key: 'member-profile-v1', method: 'PATCH', route: '/api/members/me/profile', fixtureKeys: ['profileV1'],
    request: (context) => request(context, '/api/members/me/profile', context.fixtures.profileV1),
  }),
  writeEndpoint({
    key: 'member-profile-v11', method: 'PATCH', route: '/api/members/me/profile', fixtureKeys: ['profileV11'],
    request: (context) => request(context, '/api/members/me/profile', context.fixtures.profileV11, '1.1'),
  }),
  writeEndpoint({
    key: 'member-block', method: 'POST', route: '/api/members/me/blocks', fixtureKeys: ['blockedMemberIds'],
    request: (context) => request(context, '/api/members/me/blocks', { memberId: selectFixture(context, 'blockedMemberIds') }),
  }),
  writeEndpoint({
    key: 'member-unblock', method: 'DELETE', route: '/api/members/me/blocks/{targetMemberId}', fixtureKeys: ['blockedMemberIds'],
    request: (context) => request(context, `/api/members/me/blocks/${encode(selectFixture(context, 'blockedMemberIds'))}`, null),
  }),
  writeEndpoint({
    key: 'bookmark-add', method: 'POST', route: '/api/bookmarks', fixtureKeys: ['bookmarkFoodIds'],
    request: (context) => request(context, '/api/bookmarks', { foodId: selectFixture(context, 'bookmarkFoodIds') }),
  }),
  writeEndpoint({
    key: 'bookmark-remove', method: 'PATCH', route: '/api/bookmarks/{foodId}', fixtureKeys: ['bookmarkFoodIds'],
    request: (context) => request(context, `/api/bookmarks/${encode(selectFixture(context, 'bookmarkFoodIds'))}`, null),
  }),
  writeEndpoint({
    key: 'review-create', method: 'POST', route: '/api/reviews', fixtureKeys: ['scanHistoryFoodIds'],
    request: (context) => request(context, '/api/reviews', {
      foodId: selectFixture(context, 'scanHistoryFoodIds'),
      ...reviewBody(context, 'create'),
    }),
  }),
  writeEndpoint({
    key: 'review-update', method: 'PATCH', route: '/api/reviews/{reviewId}', fixtureKeys: ['reviewIds'],
    request: (context) => request(
      context,
      `/api/reviews/${encode(selectFixture(context, 'reviewIds'))}`,
      reviewBody(context, 'update'),
    ),
  }),
  writeEndpoint({
    key: 'review-delete', method: 'DELETE', route: '/api/reviews/{reviewId}', fixtureKeys: ['reviewIds'],
    request: (context) => {
      const reviewId = uniqueFixture(context, 'reviewIds');
      return reviewId === null ? null : request(context, `/api/reviews/${encode(reviewId)}`, null);
    },
  }),
  writeEndpoint({
    key: 'review-like', method: 'POST', route: '/api/reviews/{reviewId}/like', fixtureKeys: ['reviewIds'],
    request: (context) => request(context, `/api/reviews/${encode(selectFixture(context, 'reviewIds'))}/like?liked=true`, null),
  }),
  writeEndpoint({
    key: 'review-unlike', method: 'POST', route: '/api/reviews/{reviewId}/like', fixtureKeys: ['reviewIds'],
    request: (context) => request(context, `/api/reviews/${encode(selectFixture(context, 'reviewIds'))}/like?liked=false`, null),
  }),
  writeEndpoint({
    key: 'report-create', method: 'POST', route: '/api/reports', fixtureKeys: ['reportReviewIds'],
    request: (context) => {
      const reviewId = uniqueFixture(context, 'reportReviewIds');
      return reviewId === null ? null : request(context, '/api/reports', {
        targetType: 'REVIEW',
        targetId: reviewId,
        reason: 'OTHER',
        detail: `[load:${context.runId}] report create`,
      });
    },
  }),
  writeEndpoint({
    key: 'image-upload-url', method: 'POST', route: '/api/images/upload-url',
    request: (context) => request(context, '/api/images/upload-url', {
      purpose: 'MENU_SCAN', contentType: 'image/jpeg', contentLength: 1234,
    }),
  }),
  writeEndpoint({
    key: 'image-complete', method: 'POST', route: '/api/images/complete', fixtureKeys: ['imageCompleteFixtures'],
    request: (context) => {
      const fixture = uniqueFixture(context, 'imageCompleteFixtures');
      return fixture === null ? null : request(context, '/api/images/complete', fixture);
    },
  }),
  writeEndpoint({
    key: 'order-create-no-location', method: 'POST', route: '/api/orders',
    fixtureKeys: ['orderFixtures'], request: (context) => orderRequest(context, false),
  }),
  writeEndpoint({
    key: 'order-create-location', method: 'POST', route: '/api/orders', kind: 'external',
    fixtureKeys: ['orderFixtures', 'placeLatitude', 'placeLongitude'], request: (context) => orderRequest(context, true),
  }),
];
