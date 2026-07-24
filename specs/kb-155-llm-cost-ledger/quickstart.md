# Quickstart: KB-155 LLM 호출 비용 원장 검증

## 1. 테스트로 검증 (기본)

```bash
./gradlew :infra:llm:test --tests "*OpenAiMenuBoardVisionExtractorTest*"   # 발행 시점·반올림·격리
./gradlew :domain:metering:test --tests "*LlmCallCostServiceTest*"             # 영속·정밀도
./gradlew :app:api:test --tests "*LlmCallCostEventListenerTest*"           # 비동기 소비·실패 격리
./gradlew test -Dkotest.tags='!arch'                                       # 전체 회귀
```

## 2. 로컬 실기동 검증 (선택 — 실제 vision 키 필요)

1. `SPRING_PROFILES_ACTIVE=local` + `kbap.llm.vision.enabled=true` + api key 로 `:app:api:bootRun`.
2. presigned 업로드 → `POST /api/v1/images/complete` → `POST /api/v1/scans` 1회 수행.
3. 원장 확인:

```sql
SELECT model_name, input_tokens, output_tokens, cost_usd, cost_krw, created_at
FROM llm_call_cost ORDER BY id DESC LIMIT 5;
```

- 스캔 1회당 1행, 금액이 애플리케이션 로그(`vision 토큰 사용량 ... costUsd=... costKrw=...`)와 일치해야 한다.

## 3. 관리자 총비용 집계 (이 기능의 목적)

```sql
-- 누적 총비용
SELECT SUM(cost_usd) AS total_usd, SUM(cost_krw) AS total_krw FROM llm_call_cost;

-- 월별·모델별
SELECT DATE_FORMAT(created_at, '%Y-%m') AS month, model_name,
       COUNT(*) AS calls, SUM(cost_krw) AS krw
FROM llm_call_cost GROUP BY month, model_name ORDER BY month;
```

## 4. 실패 격리 수동 확인 (선택)

- DB 를 read-only 로 두거나 테이블을 잠근 상태에서 스캔 요청 → 스캔 응답은 정상 200, 서버 로그에 기록 실패 error 로그만 남는다.
