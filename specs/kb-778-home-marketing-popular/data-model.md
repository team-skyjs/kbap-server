# Data Model: 홈 인기 음식 마케팅 풀

## 신규 테이블 — `home_marketing_popular_food` (Flyway `V2026.10.13.20.42.36__home_marketing_popular_food_table.sql`)

| 컬럼 | 타입 | 제약 | 의미 |
|------|------|------|------|
| `id` | BIGINT AI | PK | BaseEntity |
| `food_id` | BIGINT | NOT NULL, UNIQUE `uq_home_marketing_popular_food_food_id`, FK → `food.id` | 풀에 든 음식 |
| `status` | ENUM('ACTIVE','DELETED') | NOT NULL | 소프트 삭제(BaseEntity) — DELETED 는 풀에서 제외 |
| `created_at`/`updated_at` | DATETIME(6) | NOT NULL | BaseEntity |

- 엔티티 `com.kbap.common.domain.food.model.HomeMarketingPopularFood(foodId)`, 리포지토리 `HomeMarketingPopularFoodJpaRepository.findAllFoodIds()`(JPQL projection, `@SQLRestriction` 로 ACTIVE 만).
- 운영자가 DB 에서 직접 INSERT / `status=DELETED`. 관리자 API 없음.

## 버전 판정 — `ApiVersions` (`com.kbap.api.core`, 기존)

- `HOME_MARKETING_POPULAR = "1.2"` — `WebConfig.addSupportedVersions` 에 등록.
- `isAtLeast(requested: String, minimum: String)` — 홈은 `@RequestHeader(X-API-Version)` 문자열을 넘긴다(`AuthController` 선례).

## 홈 조립 — `HomeService.getHome(memberId, lang, marketingPopular)`

| `marketingPopular` | 인기 음식 출처 | READY 필터 | 선택 |
|------|-------------|-----------|------|
| false | `PopularFoodIdCache.getPopularFoodIds(10)` → `FoodService.getPopularFoods` | SQL(`findPopular`)이 적용 | 집계 순서 10 |
| true | `FoodService.getRandomMarketingPopularFoods(size=10)`: `findAllFoodIds()` → `findByIdIn` | `Food.isReady()` 후필터 | `shuffled().take(10)` |

## 기존 엔티티 — `Food` (변경 없음)

- 노출 가능 조건: `status = ACTIVE`(`@SQLRestriction` 자동) ∧ `contentStatus = READY`(`isReady()`).
