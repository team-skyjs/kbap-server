const commonOptions = {
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function profiles(env) {
  return {
    smoke: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '30s',
    },
    read: {
      executor: 'constant-arrival-rate',
      rate: Number(env.RATE || 5),
      timeUnit: '1s',
      duration: env.DURATION || '3m',
      preAllocatedVUs: Number(env.PRE_VUS || 20),
      maxVUs: Number(env.MAX_VUS || 200),
    },
    write: {
      executor: 'constant-arrival-rate',
      rate: Number(env.RATE || 1),
      timeUnit: '1s',
      duration: env.DURATION || '2m',
      preAllocatedVUs: Number(env.PRE_VUS || 10),
      maxVUs: Number(env.MAX_VUS || 50),
    },
    external: {
      executor: 'per-vu-iterations',
      vus: Number(env.VUS || 1),
      iterations: Number(env.ITERATIONS || 1),
      maxDuration: env.MAX_DURATION || '5m',
    },
  };
}

export function buildOptions(kind, env) {
  const profileName = env.PROFILE || kind;
  const profile = profiles(env)[profileName];
  if (!profile) {
    throw new Error(`unknown PROFILE: ${profileName}`);
  }

  return {
    ...commonOptions,
    scenarios: {
      [profileName]: profile,
    },
    thresholds: {
      checks: ['rate>0.99'],
      http_req_failed: ['rate<0.01'],
      dropped_iterations: ['count==0'],
      ...(kind === 'read'
        ? { http_req_duration: ['p(95)<300', 'p(99)<750'] }
        : kind === 'write'
          ? { http_req_duration: ['p(95)<500', 'p(99)<1000'] }
          : {}),
    },
  };
}
