# Data Model: KB-155 LLM 호출 비용 원장

## 테이블: `llm_call_cost`

외부 LLM 1회 호출의 과금 스냅샷. append-only(수정·삭제 창구 없음), FK 없음(독립 원장).

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | BaseEntity 공통 |
| model_name | VARCHAR(100) | NOT NULL | 실제 응답 모델명(`ChatResponse.metadata.model`), 폴백=구성값. 예: `gpt-4o-mini-2024-07-18` |
| input_tokens | BIGINT | NOT NULL | prompt 토큰 수(usage 누락 시 0) |
| output_tokens | BIGINT | NOT NULL | completion 토큰 수(usage 누락 시 0) |
| cost_usd | DECIMAL(12,6) | NOT NULL | `LlmPricing.costUsd` 결과, HALF_UP 6자리 |
| cost_krw | DECIMAL(14,2) | NOT NULL | `LlmPricing.costKrw`(환율 1500) 결과, HALF_UP 2자리 |
| status | VARCHAR(20) | NOT NULL | BaseEntity 공통(ACTIVE/DELETED) — 소프트삭제 구조 공유, 삭제 기능은 노출 안 함 |
| created_at | DATETIME(6) | NOT NULL | BaseEntity 공통 — 기간 집계 기준 시각 |
| updated_at | DATETIME(6) | NOT NULL | BaseEntity 공통 |

인덱스: `idx_llm_call_cost_created_at (created_at)` — 관리자 기간 집계 질의용.

정밀도 한도: DECIMAL(12,6) USD ≈ 최대 999,999.999999 — 단건 vision 호출 비용(수 센트)과 수년 누적 합산 모두 여유. DECIMAL(14,2) KRW 동일.

## 엔티티: `LlmCallCost` (`com.kbap.domain.scan.model`)

- `BaseEntity` 상속(id·status·createdAt·updatedAt 공통 — 자체 id/시각 금지).
- 필드: `modelName: String`, `inputTokens: Long`, `outputTokens: Long`, `costUsd: BigDecimal`, `costKrw: BigDecimal` — 전 필드 기본값으로 no-arg 자동 생성(kotlin-jpa).
- `@Column(length = 100)`(modelName), `@Column(precision = 12, scale = 6)`/`(precision = 14, scale = 2)` — Flyway 와 일치.
- 도메인 메서드 없음 — 순수 기록 스냅샷(값은 이벤트 생성 시점에 확정, 엔티티는 저장만).

## 이벤트: `LlmCallCostIncurred` (`com.kbap.core.llm`)

Spring-free 데이터 클래스(스프링 이벤트 페이로드로 사용):

```
LlmCallCostIncurred(
    modelName: String,
    inputTokens: Long,
    outputTokens: Long,
    costUsd: BigDecimal,   // 이미 HALF_UP scale 6
    costKrw: BigDecimal,   // 이미 HALF_UP scale 2
)
```

반올림 책임은 이벤트 생성자(extractor) — 원장과 로그가 같은 스냅샷 값을 공유해 산식 드리프트를 차단한다.

## 상태 전이

없음 — append-only. 생성만 존재(`LlmCallCostService.record`). 수정·삭제 메서드를 서비스에 두지 않는 것으로 강제(애플리케이션 규칙).
