import { appEndpoints } from './app.js';
import { foodEndpoints } from './food.js';
import { memberEndpoints } from './member.js';
import { orderEndpoints } from './order.js';
import { reviewEndpoints } from './review.js';
import { writeEndpoints } from './write.js';
import { externalEndpoints } from './external.js';

export const endpointCatalog = [
  ...appEndpoints,
  ...memberEndpoints,
  ...foodEndpoints,
  ...reviewEndpoints,
  ...orderEndpoints,
  ...writeEndpoints,
  ...externalEndpoints,
];

export const endpoints = endpointCatalog.reduce((registry, endpoint) => {
  registry[endpoint.key] = endpoint;
  return registry;
}, {});
