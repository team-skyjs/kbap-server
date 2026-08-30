const REQUIRED_KEYS = ['TARGET', 'BASE_URL', 'RUN_ID', 'REPORT_DIR'];

function required(env, key) {
  const value = env[key];
  if (!value) {
    throw new Error(`${key} is required`);
  }
  return value;
}

export function requireConfig(env) {
  for (const key of REQUIRED_KEYS) {
    required(env, key);
  }

  return {
    target: env.TARGET,
    baseUrl: env.BASE_URL.replace(/\/$/, ''),
    accessToken: env.ACCESS_TOKEN || '',
    runId: env.RUN_ID,
    reportDir: env.REPORT_DIR,
    contended: env.CONTENDED === 'true',
    scanTimeout: env.SCAN_TIMEOUT || '120s',
    startedAt: new Date().toISOString(),
  };
}
