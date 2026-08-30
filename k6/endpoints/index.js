import { appEndpoints } from './app.js';
import { foodEndpoints } from './food.js';
import { memberEndpoints } from './member.js';
import { orderEndpoints } from './order.js';
import { reviewEndpoints } from './review.js';

const catalog = [
  ...appEndpoints,
  ...memberEndpoints,
  ...foodEndpoints,
  ...reviewEndpoints,
  ...orderEndpoints,
];

export const endpoints = catalog.reduce((registry, endpoint) => {
  registry[endpoint.key] = endpoint;
  return registry;
}, {});
