# Research: k6 스캔 부하 테스트

## R-1. 실행기 — per-vu-iterations (open vs closed model)

- **Decision**: 비용 발생 스캔은 `per-vu-iterations`(VU N × iterations 1 = 정확히 N건). 일반 API 부하(후속, 별도 스크립트)는 `constant-arrival-rate`.
- **Rationale**: 스캔은 "정확히 N건" 이 비용 상한과 직결된다 — arrival-rate 는 지연 시 발사가 밀려 총량이 흔들리고, `shared-iterations` 는 총량은 맞아도 동시성이 불균일하다. `per-vu-iterations` 만 "동시 N + 총 N" 을 동시에 만족. 일반 API 는 반대로 coordinated omission 을 피하려 open model(arrival-rate)이 정석.
- **Alternatives**: `constant-vus`+duration(총량 비결정 → 비용 예측 불가, 기각), `ramping-vus`(계단이지만 총량 상한 어려움 — 계단은 스크립트 3회 실행으로 대체).

## R-2. 소셜 로그인 우회

- **Decision**: dev `JWT_SECRET`(SSM `/kbap/dev/JWT_SECRET`)으로 access token 을 직접 HS256 서명(`k6/mint-token.py`). 클레임 `sub`·`token_type=ACCESS`·`role=USER`·`iat`·`exp`.
- **Rationale**: `JwtTokenParser` 는 서명·`token_type`·`role`·`exp` 만 본다(무상태). Firebase/소셜 provider 는 REST 자동화가 불가해 정식 로그인 경로를 부하 스크립트에 넣을 수 없다. 회원 조회(`getMember`)는 티켓/업로드 단계에서 필요하므로 회원 row 는 실재해야 한다.
- **범위/안전**: **dev 전용.** prod 시크릿으로는 금지(누구든 토큰 위조 가능해짐). 부하 테스트를 prod 에 안 하기로 한 결정과 정합.
- **Alternatives**: 더미 회원 INSERT 후 정식 로그인(소셜이라 불가), 관리자 임의 토큰 발급 API 신설(앱 코드 변경 — 이 태스크 범위 밖·과함).

## R-3. 비용 안전장치

- **Decision**: (1) 체인 앞 단계 실패 시 해당 VU 는 `return` 으로 스캔 미발사, (2) 5→50→145 계단(합 200), (3) 스캔 timeout 120s 로 매달림 방지, (4) 리허설 VUS=5 를 항상 선행.
- **Rationale**: 스캔 단계에서만 과금되므로 앞 단계 실패가 비용으로 새지 않게 차단. 리허설이 토큰·경로·이미지 오류를 50원 안에서 잡는다.
- **Alternatives**: k6 `--iterations` 전역 상한(체인 중간 단계까지 세어 부정확), 서버측 rate limit(앱 변경, 범위 밖).

## R-4. 관찰 지표 매핑 + 429 분리

- **Decision**: k6 지표(`scan_duration` p95, `scan_failed`)와 Grafana(env="dev") 를 교차. 서버측: `http_server_requests_seconds` p95(스캔 vs 타 API), HikariCP active/pending, JVM heap/GC, 호스트 CPU/mem(integrations/unix), ALB(CloudWatch) 5xx·TargetResponseTime. 429(OpenAI 한도)는 스캔 응답 상태코드로 분리 집계해 앱 5xx 와 섞지 않는다.
- **Rationale**: 스캔은 LLM 응답까지 Tomcat 워커를 점유 → 병목이 앱 스레드/Hikari 인지, 외부 한도(429)인지 갈라야 판정이 선다.
- **Alternatives**: k6 요약만으로 판정(서버 내부 병목 안 보임, 기각).

## R-5. k6 결과 remote_write (옵션)

- **Decision**: 기본은 `k6 run --summary-export`(JSON) + 콘솔 요약. 필요 시 `k6 run -o experimental-prometheus-rw` 로 홈 Prometheus 에 쏘아 서버 메트릭과 겹쳐 본다(Cloudflare Access 헤더 필요).
- **Rationale**: 회차 3번짜리 소규모라 JSON+대시보드 캡처로 충분. remote_write 는 여유 시 상관 그래프용.
- **Alternatives**: k6 Cloud(외부 SaaS·비용, 기각).

## R-6. 회차 구성

- **Decision**: 5(리허설) → 50 → 145, 합 200. 각 회차 사이 서버 회복 대기(수 분).
- **Rationale**: 두 실측 지점(50·145)이 있어야 동시성 2.9배 증가 시 p95 곡선이 나온다. 200 한 방은 한 점만 남긴다. 5는 안전 리허설 겸 예산에 포함.

## R-7. 사전 시드 이미지 재사용 (2026-08-30 clarify)

- **Decision**: 이미지는 실제 업로드 체인(presign→PUT→complete)으로 **1회 시드**해 objectKey 를 얻고, 부하 루프는 **티켓+스캔**만 반복하며 그 키를 재사용한다. objectKey 는 시드 스크립트가 출력해 `-e SCAN_IMAGE_PATH` 로 주입(커밋 안 함).
- **Rationale**: `scanMenuBoardImageV2` 가 imagePath 의 완료·소유를 검증하지 않아(코드 확인) 같은 키를 200회 재사용해도 스캔이 거부하지 않는다. 업로드 I/O(presign·PUT·complete)를 측정 루프에서 빼면 회차가 순수 스캔(LLM) 엔드포인트를 재현성 있게 측정한다. S3 잡음·업로드 지연이 스캔 p95 에 섞이지 않는다.
- **주의**: 티켓(L1)은 1회용(jti 예약)이라 매 스캔 새로 발급해야 한다 — 티켓까지 사전 발급 재사용은 불가. 스캔은 imagePath 로 외부 LLM 이 공개 URL 을 fetch 하므로 시드는 공개 프리픽스에 있어야 한다(실제 체인 시드가 보장).
- **Alternatives**: 전체 체인 매 반복(업로드 지연이 스캔 측정 오염, 기각), 버킷 직접 put(공개 URL 매핑 수동 보장 필요·앱 경로 포맷 불일치 위험, 기각), 기존 dev 이미지 재사용(키를 수소문해야 함, 차선).
