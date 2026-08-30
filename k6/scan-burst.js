import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'https://dev.kbap.site';
const VUS = Number(__ENV.VUS || 50);
const IMAGE_PATH = __ENV.SCAN_IMAGE_PATH;
const SCAN_TIMEOUT = __ENV.SCAN_TIMEOUT || '120s';
if (!IMAGE_PATH) throw new Error('SCAN_IMAGE_PATH required — run seed-image.js first');

const scanDuration = new Trend('scan_duration', true);
const scanFailed = new Counter('scan_failed');
const scanRateLimited = new Counter('scan_rate_limited');
const scanVisionUnavailable = new Counter('scan_vision_unavailable');

const jsonHeaders = {
  'X-API-Version': '1.0',
  'Authorization': `Bearer ${__ENV.ACCESS_TOKEN}`,
  'Content-Type': 'application/json',
};

export const options = {
  discardResponseBodies: false,
  scenarios: {
    scan_burst: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '5m' },
  },
  thresholds: {
    // rate-limit 검증 런에서는 실패가 예상되므로 abortOnFail 없이 계측만 한다
    scan_duration: ['p(95)<300000'],
  },
};

export default function () {
  const ticketRes = http.post(`${BASE}/api/scans/tickets`, null, { headers: jsonHeaders });
  if (!check(ticketRes, { 'L1 ticket 200': (r) => r.status === 200 })) {
    scanFailed.add(1);
    console.error(`L1 ${ticketRes.status}: ${String(ticketRes.body).slice(0, 200)}`);
    return;
  }
  const ticket = ticketRes.json('payload.ticket');

  const scanRes = http.post(
    `${BASE}/api/scans?lang=en&currency=KRW`,
    JSON.stringify({ imagePath: IMAGE_PATH }),
    {
      headers: { ...jsonHeaders, 'X-API-Version': '2.0', 'X-Scan-Ticket': ticket },
      timeout: SCAN_TIMEOUT,
      tags: { step: 'scan' },
    },
  );
  scanDuration.add(scanRes.timings.duration);
  // 스캔 결과는 성공/실패와 무관하게 항상 체크에 담는다(실패가 checks 에 반영되도록)
  const scanOk = check(scanRes, { 'L2 scan 200': (r) => r.status === 200 });
  if (!scanOk) {
    const body = String(scanRes.body);
    if (scanRes.status === 429 || body.includes('SCAN-008')) {
      // 앱은 OpenAI rate-limit 을 503 SCAN-008 로 응답한다(KB-394) — payload.retryAfterSeconds 가 있으면 벤더 Retry-After
      scanRateLimited.add(1);
      console.warn(`L2 rate-limited ${scanRes.status}: ${body.slice(0, 160)}`);
    } else if (scanRes.status === 503 || body.includes('SCAN_VISION_UNAVAILABLE') || body.includes('SCAN-')) {
      // SCAN-006(서버 장애·quota 소진) / SCAN-002(인식 실패)
      scanVisionUnavailable.add(1);
      console.warn(`L2 ${scanRes.status} vision-unavailable: ${body.slice(0, 160)}`);
    } else {
      scanFailed.add(1);
      console.error(`L2 ${scanRes.status}: ${body.slice(0, 200)}`);
    }
  }
}
