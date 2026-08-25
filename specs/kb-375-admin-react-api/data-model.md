# Data Model: KB-375

## 신규 테이블

### admin_audit_log (신규)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT AI | PK | |
| admin_account_id | BIGINT | NOT NULL, idx | 조작자(admin_account.id) |
| action | VARCHAR(50) | NOT NULL, idx | `FOOD_UPDATE`·`FOOD_APPROVE`·`FOOD_REJECT`·`FOOD_TRANSITION`·`FOOD_DELETE`·`FOOD_RESTORE`·`FOOD_RECOLLECT`·`FOOD_IMAGE_REGENERATE`·`FOOD_IMAGE_REPLACE`·`FOOD_BULK`·`FOOD_SEED`·`CONTENT_OUTBOX_REQUEUE`·`CONTENT_OUTBOX_CANCEL`·`VECTOR_RETRY`·`VECTOR_ENQUEUE`·`IMAGE_COLLECT`·`IMAGE_RESUBMIT`·`MEMBER_STATUS`·`MEMBER_PROFILE_RESET`·`MEMBER_SCAN_UNLOCK`·`MEMBER_WITHDRAW`·`MEMBER_WITHDRAW_FAILED`·`APP_VERSION_UPDATE`·`ADMIN_LOGIN`·`ADMIN_LOGIN_LOCKED` |
| target_type | VARCHAR(30) | NOT NULL | `FOOD`·`MEMBER`·`CONTENT_OUTBOX`·`VECTOR_OUTBOX`·`IMAGE_BATCH`·`APP_VERSION`·`ADMIN_ACCOUNT` |
| target_id | BIGINT | NULL | 대상 식별자(일괄 작업은 NULL + note 에 ids) |
| before_json | JSON | NULL | 변경 전 필드 스냅샷(변경 필드만) |
| after_json | JSON | NULL | 변경 후 |
| note | VARCHAR(500) | NULL | 사유·일괄 ids·실패 메시지 |
| status / created_at / updated_at | BaseEntity | | 삭제 API 없음(불변) |

인덱스: `idx_admin_audit_target(target_type, target_id, id)`, `idx_admin_audit_admin(admin_account_id, id)`, `idx_admin_audit_action(action, id)`.

엔티티: `com.kbap.common.domain.admin.model.AdminAuditLog`, 리포지토리 `AdminAuditLogJpaRepository`(페이지 조회 — 동적 조건은 `Specification` 대신 네이티브 Custom 1개).

## 기존 테이블 변경

### member

| 컬럼 | 타입 | 의미 |
|---|---|---|
| suspended_at | DATETIME(6) NULL | 정지 시각(ACTIVE 복귀 시 NULL) |
| suspend_reason | VARCHAR(500) NULL | 정지 사유 |

`Member.suspend(reason)`: ACTIVE→SUSPENDED + 두 컬럼 세팅. `Member.reinstate()`: SUSPENDED→ACTIVE + NULL. `Member.unlockScan()`: scanUnlocked=true. `Member.resetNickname()`: `"사용자{id}"`. `Member.resetProfileImage()`: profileImageUrl=null.

### food_content_outbox

| 변경 | 내용 |
|---|---|
| ENUM 확장 | `outbox_status` ENUM('PENDING','SENT','COMPLETE','CANCELED') |
| 컬럼 추가 | `last_error` VARCHAR(500) NULL, `last_failed_at` DATETIME(6) NULL |
| 인덱스 | `idx_food_content_outbox_status_sent(outbox_status, sent_at)` — 고착 조회 |

`FoodContentOutbox.requeue()`: SENT→PENDING, sentAt=null. `cancel()`: PENDING/SENT→CANCELED. `recordPublishFailed(ids, error)` 네이티브가 last_error/last_failed_at 갱신.

### food

스키마 변경 없음. 도메인 메서드 추가: `allowedTransitions()`, `transition(FoodTransition, reason?)`, `replaceImage(ref)`, `restore()`(status ACTIVE — 네이티브 UPDATE 로 수행하고 엔티티는 재조회). `approve()` 전제 강화(ingredients != null && imageRef != null).

## 마이그레이션 (생성 시각 timestamp, 서로 독립)

1. `V2026.08.25.HH.mm.ss__admin_audit_log_table.sql` — 테이블 + 인덱스 3
2. `V2026.08.25.HH.mm.ss__member_suspension_columns.sql` — 컬럼 2
3. `V2026.08.25.HH.mm.ss__food_content_outbox_cancel_and_error.sql` — ENUM 확장 + 컬럼 2 + 인덱스 1

리비전 공존 주의(메모리 `schema-change-revision-coexistence`): 신 컬럼은 전부 NULL 허용, ENUM 은 값 추가만 → 구 코드가 신 스키마 위에서 돌아도 안전. `AvoidanceCatalogSeedSyncTest` 류 시드-경로 결합 없음.

## 주요 값 객체·enum (신규)

- `FoodTransition { APPROVE, REJECT, RESUBMIT, UNPUBLISH }` — `common.domain.food.model`. 허용 표:

| from | transition | to | 전제 | 부수효과 |
|---|---|---|---|---|
| PENDING_REVIEW | APPROVE | READY | ingredients ≠ null ∧ imageRef ≠ null | 벡터 UPSERT 예약 |
| PENDING_REVIEW | REJECT | FAILED | reason 필수 | attempts++, 사유 기록 |
| FAILED | RESUBMIT | PENDING_REVIEW / PENDING_IMAGE | description ≠ placeholder ∧ ingredients ≠ null | imageRef 없으면 PENDING_IMAGE. 실패 유형·사유 clear |
| READY | UNPUBLISH | PENDING_REVIEW | — | 벡터 DELETE 예약 |

- `FoodContentStatus.displayName`(확인 필요·이미지 대기·승인 대기·준비 완료), `FoodContentOutboxStatus.CANCELED`.
- `AdminAuditAction`, `AdminAuditTargetType` — `common.domain.admin.model` enum(위 표 값).
- `FieldError(field, code, message)` — 검증 결과(`api.admin`).
- `AdminFoodRow`·`AdminMemberRow` — 네이티브 목록 프로젝션(`common.domain.{food,member}.dto`), 삭제/탈퇴 행 포함 가능(`status` 필드 동봉).

## 관계·참조

전부 id 참조(연관관계 없음 — 헌법 IV). 감사 로그 `target_id` 는 FK 없음(대상 삭제 후에도 이력 유지).
