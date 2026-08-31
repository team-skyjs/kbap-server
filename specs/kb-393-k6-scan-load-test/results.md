# 회차 결과: k6 스캔 부하 테스트 (dev)

- 대상: `https://dev.kbap.site` · 회원 35(scan_unlocked) · 이미지 `test/images/scan/hansik-madang.jpg`(CDN 200)
- 스크립트: `k6/scan-burst.js`(티켓+스캔 루프, per-vu-iterations) · 단건 기준 지연 ≈15.8s(LLM 비전)
- 비용: 스캔 1건 ≈10원. 누적 상한 200건(≈2,000원).

## 회차 기록

| 회차 | 시각 | 스캔 건수 | scan p95 | scan avg | 실패(scan_failed) | 429(rate_limited) | L2 200 비율 | 누적 건수 | 비고 |
|---|---|---|---|---|---|---|---|---|---|
| 검증 VUS=1 | 2026-08-30 16:42 | 1 | 15.81s | 15.81s | 0 | 0 | 1/1 | 1 | ASCII 키 재키 후 성공 |
| 리허설 VUS=5 | 2026-08-30 16:5x | 5 | 12.15s | 9.26s | 0 | 0 | 5/5 | 6 | min 5.15s~max 12.15s, 동시5는 여유 |
| 본 VUS=50 | 2026-08-30 17:0x | 50 | 13.59s | 8.07s | 0 | 0 | 50/50 | 56 | 14.1s 완료, 동시5 대비 p95 +12%만 |
| 본 VUS=145 | 2026-08-30 17:1x | 145 | 24.54s | 13.75s | 0 | 0 | 145/145 | 201 | 27.5s 완료, med 12.4s/max 26s — 여기서 줄서기 시작 |

## 처리량·곡선 요약

- 스캔 스루풋: VUS=50 3.55 iters/s → VUS=145 5.27 iters/s (동시성 2.9배에 스루풋 1.5배 — 처리량 상한에 접근).
- p95 곡선: 1건 15.8s → 5 12.2s → **50 13.6s → 145 24.5s**. 50까지 평탄(LLM 대기가 I/O 라 워커 물려도 스루풋 유지), 145에서 대기열 형성으로 +80%.
- 전 회차 scan_failed=0 · 429=0 · L2 200 100%. 앱·벤더 한도 미도달 — 현 구성은 동시 145 스캔을 에러 없이 소화(지연만 증가).
- 누적 201건(검증1+리허설5+50+145), 비용 ≈2,010원.

## 관찰(Grafana env="dev", 부하 창 ~16:50, APM 대시보드 PDF)

| 항목 | 관측값(부하 창 피크) | 판독 |
|---|---|---|
| CPU | Process CPU max ≈0.166(단일코어 16.6%), Load avg[1m] max ≈1.5 | 여유 — CPU 병목 아님 |
| Heap | Used 8.0%(ceiling 2.63GiB), Eden 최대 160~182MiB, minor GC(G1 Evac) 소폭 | 여유 — 메모리 병목 아님 |
| HikariCP | 커넥션 10/10, **Pending 0 · timeout_total 0**, Acquire Time 낮음 | **DB 풀 병목 아님** — 스캔은 LLM 대기 중 커넥션을 안 물고 있음 |
| 워커 스레드 | Daemon threads 최대 ≈80 (Tomcat max 200) | 실 동시 in-flight ≈72(Little: 5.27/s×13.75s), 워커 한도 200에 여유 |
| 로그 | INFO 스파이크(부하 창), ERROR ≈0, WARN 소량 | 에러 없음 — k6 실패 0 과 일치 |
| HTTP 요청 | POST 200 /api/scans/tickets · POST 200 /api/scans(≈1:1) + GET 404 /** | 스캔 체인만, 4xx/5xx 실 오류 없음(404 는 봇/프로빙) |
| Response Time 패널 | **No data** | 엔드포인트별 p95 미노출 — 타 API 영향은 이 캡처로 직접 측정 불가(모니터링 갭) |
| Tomcat Threads 패널 | **No data** | 워커 스레드 수 직접 패널 미노출(daemon threads 로 대체 판독) |

## 병목 판정 (T014)

- **현 구성(dev, 동시 145)에서 앱 자원 병목은 없다.** CPU·heap·DB 풀 전부 여유였고 에러·429·5xx 0.
- p95 상승(50 13.6s → 145 24.5s)의 원인은 **워커 스레드 점유 시간**이다: 스캔 1건이 외부 LLM 응답까지 Tomcat 워커를 ~13~24s 붙잡는다(그 사이 CPU·DB 는 놀고 대기만). 동시성이 오르면 in-flight 워커가 늘어(≈72) 뒤쪽 요청이 큐잉되며 지연이 선형 이상으로 붙는다.
- 진짜 한계는 두 곳: **① Tomcat max-threads(200)** — 실 in-flight ≈72 라 아직 3배 여유. **② 외부 LLM(OpenAI) 처리량/동시 한도** — 429 미발생이나 p95 곡선이 꺾이는 걸 보면 벤더 쪽에서 줄서기가 시작된 것으로 보임(스루풋 3.55→5.27/s 로 상한 접근).
- **타 API 영향은 이번 캡처로 직접 확증 불가**(Response Time 패널 No data + 부하 창에 타 API 트래픽 없음). 다만 워커 in-flight ≈72/200 이라 헤드룸이 충분했으므로 일반 API 서빙 여력은 남아 있었다고 추정(측정 아닌 추정).

### 모니터링 갭(후속)
- APM 대시보드의 **Response Time·Tomcat Threads·Total Error 패널이 No data** — `http_server_requests_seconds` 히스토그램/Tomcat 스레드 메트릭이 이 대시보드 쿼리에 안 잡힌다. 엔드포인트별 p95·워커 수를 보려면 패널 쿼리(또는 노출 메트릭) 보강 필요. 타 API 영향을 제대로 보려면 다음엔 스캔 부하와 **동시에 일반 API 부하(constant-arrival-rate)를 낮게 병행**해야 한다.

## 메모

- 한글 파일명 키(`한식마당.jpg`)는 CDN/OpenAI fetch 실패(SCAN-002) → ASCII 키 복사로 해결. 스캔은 imagePath 소유/완료 미검증이라 재키 객체 그대로 사용 가능.


## Rate-limit 검증 (OpenAI 프로젝트 TPM=200, 2026-08-30)

- 설정: 프로젝트 `proj_yjw1ynRmz33goeUyHLsyyUgR` 의 `gpt-5.6-luna` TPM=200(RPM=1 은 미반영, TPM 이 실제로 물림). 헤더 진단으로 확인 — 텍스트 16토큰 호출은 통과, 이미지 스캔(807토큰)은 `rate_limit_exceeded / type:tokens` 로 거부.
- **한도 전파는 점진적(eventually consistent)** — 콘솔 저장 후 수 분에 걸쳐 OpenAI 엣지에 퍼진다. 세 스냅샷이 전파 곡선을 보여줌:
  - 전파 전(VUS=8 ×2): **8/8 성공** — 키가 티어 기본값(TPM 4M)으로 동작. 헤더 폴링도 RPM=5000 유지.
  - 전파 중(VUS=5): **2건 200 / 3건 503** — 노드마다 신·구 한도 엇갈림.
  - 전파 후(VUS=5): **0건 200 / 5건 503**(`scan_vision_unavailable=5`) — 단건이 이미 TPM 초과라 전부 거부.
- 실패는 **~0.8~1초 즉시**(재시도 백오프 없음).

### 발견 (개선거리)
1. **rate-limit 이 SCAN-002("메뉴판 인식에 실패했습니다")로 둔갑.** OpenAI 429 가 `MenuBoardVisionUnavailableException` 이 아닌 generic `Exception → MENU_BOARD_RECOGNITION_FAILED` 로 분류돼, 사용자/운영자가 throttle 을 "사진 불량"과 구분 못 함. 전용 에러코드 필요.
   - **서버 로그 확증(dev, 08:39:55 UTC)**: `ScanService: 메뉴판 비전 인식 실패` (WARN) ← `com.openai.errors.RateLimitException: 429 ... TPM Limit 200, Requested 1025` → `GlobalExceptionHandler: BusinessException SCAN-002 503`. 429/rate-limit 정보가 WARN 스택에만 남고 메시지·코드에는 소실 — 운영자가 "사진 품질"로 오인하기 쉬움.
2. **"요청>TPM" 은 non-transient 라 재시도 안 됨** — 즉시 503. (RPM 초과형 transient 429 는 Spring AI 재시도 경로로 다름 — 이번엔 미검증.)
3. **`x-ratelimit-*`/`Retry-After` 헤더 능동 활용 없음** — 사전 스로틀·정확한 재시도 안내 미구현.
4. 이미지 첨부·처리는 정상 확인(807토큰 = 이미지 포함, TPM 여유분으로 통과한 2건은 메뉴 추출 성공).

### k6 checks 착시 (스크립트 수정)
- rate-limit 런에서 k6 요약 `checks_succeeded 100%` 는 **티켓(L1)만** 집계된 것 — 503 스캔이 `L2 scan 200` 체크에 닿기 전 early-return 해서 스캔은 checks 에서 빠졌다. 실제 실패 신호는 `scan_vision_unavailable`·`http_req_failed`(50%)·`✓ L2 scan 200` 줄 부재로 봐야 정확.
- 수정: `scan-burst.js` 가 스캔 결과를 성공/실패 무관하게 항상 `L2 scan 200` 체크에 담도록 변경(실패가 checks 에 정직히 반영). US1 부하 회차(50/145)의 checks 100% 는 실제 성공이라 영향 없음 — 착시는 실패 나는 rate-limit 런에서만 있었고 카운터 데이터는 정확했다.

### 상태
- 테스트 본체(US1 부하 + US2 관찰 + rate-limit 검증) 완료. **주의: 검증 목적으로 OpenAI luna TPM=200 을 낮춰둔 상태 → 원복 전까지 dev 스캔 전건 503.** 원복 필수.
- 미완: 메뉴 추출 내용 육안 확인(TPM 원복 후), 위키 이관(T015), 스크립트 커밋(T016), 더미/계정 정리(T017), PR(T019).
