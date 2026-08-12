# Data Model: KB-328 벡터 아웃박스·음식 벡터 문서

## 1. MySQL — `food_vector_outbox` (신규, Flyway owner = api)

| 컬럼 | 타입 | 제약 | 의미 |
|------|------|------|------|
| id | BIGINT AUTO_INCREMENT | PK | BaseEntity |
| food_id | BIGINT | NOT NULL, FK → food(id) | 동기화 대상 음식 (JPA 연관 없음 — id 값 참조) |
| operation | ENUM('UPSERT','DELETE') | NOT NULL | 작업 종류 |
| outbox_status | ENUM('PENDING','COMPLETE','FAILED') | NOT NULL DEFAULT 'PENDING' | 처리 상태 |
| attempts | INT | NOT NULL DEFAULT 0 | 시도 횟수 |
| last_error | VARCHAR(500) | NULL | 마지막 실패 원인 (truncate 저장) |
| status | ENUM('ACTIVE','DELETED') | NOT NULL | BaseEntity 소프트삭제 (관례상 존재, 실사용 없음) |
| created_at / updated_at | DATETIME(6) | NOT NULL | BaseEntity |

인덱스: `idx_food_vector_outbox_status_id (outbox_status, id)` — 커서 페이징(기존 콘텐츠 아웃박스와 동형), `idx_food_vector_outbox_food (food_id)` — FK·조회.

**엔티티**: `com.kbap.common.domain.food.model.FoodVectorOutbox` (BaseEntity 상속, public). 팩토리 `upsert(foodId)` / `delete(foodId)`. enum: `FoodVectorOutboxOperation { UPSERT, DELETE }`, `FoodVectorOutboxStatus { PENDING, COMPLETE, FAILED }`.

**리포지토리**: `com.kbap.common.domain.food.FoodVectorOutboxJpaRepository` —
- `findPendingAfterId(afterId, limit)` — id 커서 페이징 (기존 패턴 복제)
- `existsByFoodIdAndOperationAndOutboxStatus(foodId, op, PENDING)` — 생성 시 중복 억제
- 완료/실패/재처리 상태 전이 쿼리 + 대시보드용 `countByOutboxStatus`·FAILED 목록 조회

### 상태 전이

```text
(생성) → PENDING
PENDING → COMPLETE   배치 처리 성공 (hash 동일로 인한 스킵 포함)
PENDING → PENDING    처리 실패: attempts+1, last_error 기록 (attempts < 5)
PENDING → FAILED     처리 실패: attempts+1 결과가 5 이상
FAILED  → PENDING    관리자 재처리 (attempts=0, last_error 유지)
```

### 생성 규칙 (api, 전이 트랜잭션 내부)

| 경로 | 조건 | 생성 |
|------|------|------|
| 승인 (`AdminFoodContentReviewService`) | `Food.approve()` 가 실제 PENDING_REVIEW → READY 전이 | UPSERT |
| 수정 (`AdminFoodService.updateFood`) | 수정 후 content_status = READY | UPSERT |
| 수정 (동상) | READY → 비READY 로 변경 | DELETE |
| 삭제 (`AdminFoodService.deleteFood`) | 항상 | DELETE |
| 백필 (Flyway 1회) | content_status='READY' AND status='ACTIVE' | UPSERT |

동일 (food_id, operation) 의 PENDING 이 이미 있으면 생성을 생략한다(중복 억제 — 없어도 수렴하지만 행 낭비 방지). 서로 다른 operation 은 억제하지 않는다 — 각 건이 처리 시점 최신 상태를 재검사하므로 순서와 무관하게 수렴.

## 2. DocumentDB — `kbap.foods` 문서 (KB-319 계약 확장)

```json
{
  "foodId": 12,                                  // unique index (기존)
  "name": "김치찌개",                              // koreanName (질의 규약과 정합)
  "longDescription": "김치와 돼지고기를 …",
  "imageRef": "images/food/12.webp",
  "embedding": [0.01, …],                        // float 256, cosine 벡터 인덱스 (기존)
  "embeddingHash": "sha256:…",                   // SHA-256(model|dimension|name\nlongDescription)
  "embeddingModel": "amazon.titan-embed-text-v2:0",
  "embeddingDimension": 256,
  "indexedAt": "2026-08-12T10:00:00Z"
}
```

- upsert = foodId 기준 문서 전체 교체. 기존 문서(구 스키마, hash 없음)는 첫 처리에서 hash 불일치 → 재임베딩·교체로 수렴.
- 읽기 경로(`DocumentDbSimilarFoodSearcher`)는 `foodId`·`embedding`·score 만 소비 — 확장 필드는 검색과 무호환 없음.

## 3. 배치 처리 판정 (foodVectorSyncJob)

PENDING 아웃박스 1건에 대해, 처리 시점 MySQL 최신 상태 기준:

| 아웃박스 | 음식 상태 (처리 시점) | 문서 hash | 행동 |
|----------|----------------------|-----------|------|
| UPSERT | READY·ACTIVE, longDescription 있음 | 문서 없음 | 임베딩 → insert → COMPLETE |
| UPSERT | 동상 | hash 동일 | 임베딩 생략, 메타데이터만 갱신 → COMPLETE |
| UPSERT | 동상 | hash 다름 | 재임베딩 → 교체 → COMPLETE |
| UPSERT | READY·ACTIVE, longDescription null/blank | — | 실패 기록 (attempts+1, last_error) |
| UPSERT | 비READY 또는 DELETED | — | 문서 있으면 제거 → COMPLETE (자격 재검사) |
| DELETE | 무관 | 문서 없음 포함 | 문서 제거(멱등) → COMPLETE |

임베딩·DocumentDB 호출 예외 → 실패 기록. attempts ≥ 5 → FAILED.
