import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'https://dev.kbap.site';
const IMG = open(__ENV.IMG || './menu-board.jpg', 'b');

const jsonHeaders = {
  'X-API-Version': '1.0',
  'Authorization': `Bearer ${__ENV.ACCESS_TOKEN}`,
  'Content-Type': 'application/json',
};

export const options = {
  scenarios: { seed: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '2m' } },
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const urlRes = http.post(`${BASE}/api/images/upload-url`, JSON.stringify({
    purpose: 'MENU_SCAN', contentType: 'image/jpeg', contentLength: IMG.byteLength,
  }), { headers: jsonHeaders });
  if (!check(urlRes, { 'S1 upload-url 200': (r) => r.status === 200 })) {
    console.error(`S1 ${urlRes.status}: ${String(urlRes.body).slice(0, 200)}`); return;
  }
  const upload = urlRes.json('payload');

  const putRes = http.put(upload.uploadUrl, IMG, { headers: upload.requiredHeaders, timeout: '60s' });
  if (!check(putRes, { 'S2 s3 put 200': (r) => r.status === 200 })) {
    console.error(`S2 ${putRes.status}`); return;
  }

  const completeRes = http.post(`${BASE}/api/images/complete`, JSON.stringify({
    path: upload.objectKey, contentType: 'image/jpeg', size: IMG.byteLength,
  }), { headers: jsonHeaders });
  if (!check(completeRes, { 'S3 complete 200': (r) => r.status === 200 })) {
    console.error(`S3 ${completeRes.status}: ${String(completeRes.body).slice(0, 200)}`); return;
  }
  const path = completeRes.json('payload.path') || upload.objectKey;
  console.log(`SCAN_IMAGE_PATH=${path}`);
}
