# Data Model: 음식 상세 조회 횟수 비동기 로그 적재

## 엔티티

### FoodViewLog (`common.domain.food.model`) — 테이블 `food_view_log`

상세 조회 1회 = 행 1개. append-only. 도메인 메서드 없음.

| 필드 | 컬럼 | 타입 | 제약 | 비고 |
|------|------|------|------|------|
| `id` | `id` | BIGINT | PK, AUTO_INCREMENT | `BaseEntity` |
| `foodId` | `food_id` | BIGINT | NOT NULL | 조회된 음식(id 값 참조, FK 없음 — research R5) |
| `memberId` | `member_id` | BIGINT | NULL | 게스트면 NULL(id 값 참조, FK 없음) |
| — | `status` | ENUM('ACTIVE','DELETED') | NOT NULL DEFAULT 'ACTIVE' | `BaseEntity` 소프트삭제(사용 안 함, 규약상 존재) |
| `createdAt` | `created_at` | DATETIME(6) | NOT NULL | **= 조회 시각** (research R2) |
| `updatedAt` | `updated_at` | DATETIME(6) | NOT NULL | `BaseEntity`, 갱신 없음 |
| — | `reserved_1` | VARCHAR(255) | NULL | 예비(유입 경로 등 대비, 2026-09-23 결정). 엔티티 미매핑 |
| — | `reserved_2` | VARCHAR(255) | NULL | 예비. 엔티티 미매핑 |
| — | `reserved_3` | VARCHAR(255) | NULL | 예비. 엔티티 미매핑 |

인덱스:

- `idx_food_view_log_created_food (created_at, food_id)` — "기간 내 음식별 조회 수" 집계(FR-008). `WHERE created_at >= ? GROUP BY food_id` 가 created_at 범위로 창 안의 행만 커버링 스캔한다. (Codex 리뷰 반영 2026-09-23 — 처음의 `(food_id, created_at)` 은 선두 컬럼에 조건이 없어 전체 인덱스 스캔이 됐다.)

## 이벤트

### FoodViewed (`com.kbap.api.food`)

```kotlin
data class FoodViewed(val foodId: Long, val memberId: Long?)
```

발행: `FoodService.getDetail` 성공 경로 끝. 소비: `FoodViewLogListener`(`@Async @EventListener`).

## 관계

- `FoodViewLog.foodId` → `Food.id` (id 값 참조, JPA 연관 없음)
- `FoodViewLog.memberId` → `Member.id` (id 값 참조, nullable)
- FK 없음 — 원본 삭제·탈퇴와 무관하게 행이 남는다(FR-007). 테스트의 하드 DELETE 도 막지 않는다.

## 상태 전이

없음 — 생성만 있고 갱신·삭제 없음.

## Flyway

`api/src/main/resources/db/migration/V2026.09.23.HH.mm.ss__food_view_log_table.sql` (파일 생성 시각으로 명명):

```sql
-- KB-643: 음식 상세 조회 이력. 조회 1회 = 행 1개(append-only). 인기순 음식 집계의 원본 데이터.
-- created_at 이 조회 시각이다(비동기 저장 지연은 밀리초 수준). 검색·유입 경로는 범위 밖.
-- reserved_1~3 은 예비 컬럼(유입 경로 등 대비). 용도가 정해지면 RENAME COLUMN 으로 이름을 바꾸고 엔티티에 매핑한다.
-- food_id·member_id 에 FK 를 두지 않는다: 원본 행이 사라져도 이력은 남아야 하고, 비동기 저장이 원본 삭제와 경합하지 않게 한다.

CREATE TABLE food_view_log
(
    id         BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    food_id    BIGINT                    NOT NULL,
    member_id  BIGINT                    NULL,
    status     ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)               NOT NULL,
    updated_at DATETIME(6)               NOT NULL,
    reserved_1 VARCHAR(255)              NULL,
    reserved_2 VARCHAR(255)              NULL,
    reserved_3 VARCHAR(255)              NULL,
    INDEX idx_food_view_log_created_food (created_at, food_id)
);
```

## 예비 컬럼 규칙

- `reserved_1~3` 은 **엔티티에 매핑하지 않는다**(Hibernate `ddl-auto=validate` 는 엔티티 컬럼이 스키마에 있는지만 보므로 통과). 용도 확정 시 `ALTER TABLE ... RENAME COLUMN reserved_1 TO source` + 엔티티 필드 추가 마이그레이션으로 전환한다. 값이 들어간 뒤에는 타입을 바꾸지 않는다.

## 테스트 픽스처 영향

- `api/src/test/kotlin/com/kbap/api/TestTables.kt` 의 하드코딩 목록에 `"food_view_log"` 추가(`food`·`member` 보다 앞).
