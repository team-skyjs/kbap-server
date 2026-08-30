# Quickstart: KB-394 검증

## 1. 단위 — 어댑터 예외 매핑·예산 (Spring 없음)

```bash
./gradlew :common:test
```

`OpenAiMenuBoardVisionExtractorTest` 에서: `x-should-retry:false` 429 → `MenuBoardVisionRateLimitedException(exhausted=false)` 즉시(sleep 0회) · `Retry-After:1` 후 성공 → 성공(sleep 1회, 1s) · 계속 429 → 예산 초과 시 `exhausted=true` 이고 sleep 합 ≤ 예산 · 5xx/IO → 예산 내 재시도 후 `Unavailable` · 400 → 원 예외 전파.

## 2. 통합 — 응답 코드·횟수·payload

```bash
./gradlew :api:test
```

`ScanControllerTest`: v1·v2 `SCAN-008` 503, v2 `scanCount` 불변, `payload.retryAfterSeconds` 있음/없음, 기존 SCAN-002/006 케이스 그린. `OpenApiSnapshotTest` 스캔 설명에 SCAN-008.

## 3. 로그 확인

api 테스트 로그(`-i`)에서 `메뉴판 비전 rate-limit — kind=IMMEDIATE` / `kind=EXHAUSTED` 줄과, 같은 요청에 "메뉴판 비전 인식 실패" 가 없음을 확인.

## 4. (선택) 실제 벤더로 재현

k6 `k6/scan-burst.js` 의 rate-limit 검증 런(#200 절차)을 dev 에 대해 실행하면 `scan_rate_limited` 대신 이제 503 `SCAN-008` 이 집계된다 — 스크립트의 코드 분기(현재 `SCAN-002/SCAN_VISION_UNAVAILABLE` 주석)를 SCAN-008 로 갱신해 두면 회차 비교가 된다.
