# Phase 1 Data Model — KB-302

## 1. `food` (기존 테이블 — 컬럼 1개 추가)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `content_failure_kind` | `ENUM('NOT_FOOD','JUDGE_REJECTED','INGREDIENT_GUARD')` NULL | **신규**. 마지막 수집 실패의 유형. 성공 적재 시 `NULL` 로 초기화 |

기존 컬럼 재사용: `content_review_rejection_reason`(사유 문구, VARCHAR 1000 — 10줄·1000자 절단은 엔티티가 수행), `content_review_attempts`(실패 누적).

### 엔티티 도메인 메서드 (`Food`)

```
applyContent(description, spiciness, nameTranslations, descriptionTranslations, ingredients)
  ├─ 필드 갱신
  ├─ contentFailureKind = null, contentReviewRejectionReason = null
  └─ 상태 결정
       contentStatus == READY          → 변경 없음            (서비스 무중단)
       imageRef 있음                    → PENDING_REVIEW       (사진 재활용 — 이미지 생성 후보 제외)
       imageRef 없음                    → PENDING_IMAGE

recordContentFailure(kind, reason)
  ├─ contentFailureKind = kind, contentReviewRejectionReason = reason(절단)
  ├─ contentReviewAttempts++
  └─ 상태 결정
       contentStatus == READY          → 변경 없음            (서비스 중 데이터 보존)
       그 외                            → FAILED
```

기존 전이(`approve`·`reject`·`attachImage`)와 상태 enum(`FoodContentStatus` 4값)은 변경하지 않는다. `applyContent` 는 `require` 로 진입 상태를 제한하지 않는다 — 어떤 상태에서든 재수집이 가능해야 한다.

**검증 책임**: 9개 언어 전수·빈 값 불가, `spiciness` 0~10, `ingredients` non-null 은 **요청 DTO(요청 경계)** 가 검증한다(헌법 V). 엔티티는 확정값을 받는다.

## 2. `food_content_outbox` (신규 — 아웃박스)

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | `BIGINT` | PK, AUTO_INCREMENT | BaseEntity |
| `food_id` | `BIGINT` | NOT NULL | 대상 음식. **JPA 연관 없음 — 값 참조** |
| `display_name` | `VARCHAR(255)` | NOT NULL | 발행 시점의 표시명(큐 메시지에 실림) |
| `outbox_status` | `ENUM('PENDING','SENT')` | NOT NULL, DEFAULT `'PENDING'` | 도메인 상태 — BaseEntity 의 `status` 와 컬럼명 분리 |
| `attempts` | `INT` | NOT NULL, DEFAULT 0 | 발행 시도 횟수 |
| `sent_at` | `DATETIME(6)` | NULL | 발행 성공 시각 |
| `status` | `ENUM('ACTIVE','DELETED')` | NOT NULL, DEFAULT `'ACTIVE'` | BaseEntity 소프트 삭제 |
| `created_at` / `updated_at` | `DATETIME(6)` | NOT NULL | BaseEntity |

인덱스:

- `idx_fco_status_id (outbox_status, id)` — 발행 대상 조회
- `idx_fco_food_id (food_id)` — 중복 확인·이력 조회

외래키는 두지 않는다(소프트 삭제 구조 — 프로젝트 규약).

### 수명주기

```
생성(PENDING) ──발행 성공──▶ SENT (sent_at 기록, 행 보존)   ← 발행은 후속 티켓
      ▲                └─ 발행 실패 → PENDING 유지, attempts++, 다음 주기 재시도
      │
  ┌───┴────────────────────────────┐
  │ 스캔 미보유 음식 등록 (FoodService.createIncomplete — 같은 트랜잭션)
  │ 관리자 일괄 재수집 (AdminFoodService.requestRecollect)
  └────────────────────────────────┘
```

같은 `food_id` 에 `PENDING` 행이 있으면 새로 만들지 않는다(`existsByFoodIdAndOutboxStatus`). `SENT` 행은 남으며 두 번째 재수집을 막지 않는다.

## 3. 큐 메시지 값 타입 — **후속 발행 티켓에서 생성**(이번 범위 아님)

아래는 합의된 형태만 남긴 것이다. 이번 작업에서는 `common.port.mq` 패키지도, 어댑터도 만들지 않는다.


```
FoodContentEvent(outboxId: Long, foodId: Long, displayName: String)
```

`outboxId` 는 SQS 배치 엔트리 id 로만 쓰이고 **메시지 본문에는 담지 않는다**. 본문은 `{ "foodId": .., "displayName": ".." }`.

seam:

```
FoodContentEventPublisher.publish(messages: List<FoodContentEvent>): Set<Long>
    // 반환 = 발행에 성공한 outboxId 집합 (부분 실패 허용)
```

Spring·JPA·AWS 타입이 시그니처에 드러나지 않는다(ArchUnit 이 `common.port` 의 Spring-free 를 강제).

## 4. Flyway 마이그레이션

| 파일 | 내용 |
|---|---|
| `V2026.08.11.04.05.00__food_content_failure_kind.sql` | `food` 에 `content_failure_kind` 컬럼 추가 |
| `V2026.08.11.04.06.00__food_content_outbox_table.sql` | `food_content_outbox` 테이블 생성 |

(실제 파일명의 시각은 생성 시점으로 채운다 — 점 구분 timestamp 규칙.)

**주의**: 두 마이그레이션은 서로의 실행 순서에 의존하지 않는다(`out-of-order=true` 전제).

## 5. 배포 순서 안전성 (블루/그린)

새 코드가 배포되기 전에 마이그레이션이 먼저 적용되고, 그 사이 **구 코드가 신 스키마 위에서 돈다**:

- `content_failure_kind` 는 NULL 허용이라 구 코드의 `food` INSERT/UPDATE 가 깨지지 않는다.
- `food_content_outbox` 는 구 코드가 모르는 테이블이라 무해하다.
- 구 코드에는 적재 API 가 없으므로 람다가 이 시점에 호출하면 404 → DLQ → 재처리. 유실은 아니다.
