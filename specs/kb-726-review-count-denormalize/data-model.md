# Data Model: 홈 리뷰 인기 음식 — 음식별 리뷰 수 역정규화

**Date**: 2026-10-08 | **Plan**: [plan.md](plan.md)

## 스키마 변경 (Flyway, api 소유)

### `food` 테이블

| 변경 | 정의 | 비고 |
|------|------|------|
| 컬럼 추가 | `review_count INT NOT NULL DEFAULT 0` | 현재 유효(status=ACTIVE) 리뷰 수. 구 코드 INSERT 에 영향 없음(DEFAULT). |
| 인덱스 추가 | `idx_food_review_count_recent (status, content_status, review_count DESC, id DESC)` | 레일 조회 전용. 등치 2개 + 정렬 2개 → 상위 N 행 인덱스 순서 읽기. |
| 백필 | `UPDATE food f LEFT JOIN (SELECT food_id, COUNT(*) cnt FROM food_review WHERE status='ACTIVE' GROUP BY food_id) x ON x.food_id = f.id SET f.review_count = COALESCE(x.cnt, 0)` | 절대값 대입, 재실행 가능(멱등). |

마이그레이션 파일 하나(`V2026.10.08.HH.mm.ss__food_review_count.sql`)에 위 세 가지를 순서대로 담는다. 다른 미적용 마이그레이션에 의존하지 않는다(`food_review` 는 2026-07-29 생성).

## 엔티티

### `Food` (`common.domain.food.model.Food`)

| 필드 | 타입 | 매핑 | 규칙 |
|------|------|------|------|
| `reviewCount` | `Int` | `@Column(name = "review_count", nullable = false, columnDefinition = "int not null default 0")`, 기본값 0, 본문 프로퍼티 | 엔티티 setter 로 바꾸지 않는다. 변경은 리포지토리 벌크 UPDATE 로만. 읽기 전용 값으로 취급. |

`@Version version` 은 그대로다. 벌크 UPDATE 는 version 을 올리지 않는다(검증: 리포지토리 테스트).

### `Review` — 변경 없음

## 리포지토리 (`FoodJpaRepository`)

| 메서드 | 쿼리 | 반환 |
|--------|------|------|
| `findMostReviewed(size)` **교체** | 네이티브 `select f.* from food f where f.status = 'ACTIVE' and f.content_status = 'READY' and f.review_count > 0 order by f.review_count desc, f.id desc limit :size` | `List<Food>` (시그니처 불변) |
| `increaseReviewCount(foodId)` **신규** | JPQL `update Food f set f.reviewCount = f.reviewCount + 1 where f.id = :foodId`, `@Modifying(clearAutomatically = true, flushAutomatically = true)` | `Int`(갱신 행 수, 호출자는 무시) |
| `decreaseReviewCount(foodId)` **신규** | JPQL `update Food f set f.reviewCount = f.reviewCount - 1 where f.id = :foodId`, 같은 `@Modifying` | `Int` |

`@SQLRestriction("status = 'ACTIVE'")` 는 JPQL 엔티티 UPDATE 에 적용되지 않을 수 있으므로 where 는 PK 만 둔다 — 삭제된 음식의 리뷰를 관리자가 지우는 경우에도 카운터가 맞게 내려간다(스펙 Edge Case: 음식 삭제돼도 값 유지·갱신).

## 상태 전이 — 카운터 증감 규칙

| 사건 | 경로 | 카운터 | 트랜잭션 |
|------|------|--------|----------|
| 리뷰 작성 | `ReviewService.createReview`(앱·리뷰 봇 공통) | +1 | 작성 트랜잭션 안, `memberService.increaseReviewCount` 직후 |
| 본인 삭제 | `deleteReview` → `softDelete` | -1 | 삭제 트랜잭션 안, `memberService.decreaseReviewCount` 직후 |
| 관리자 삭제(작성자 존재) | `deleteForModeration` → `softDelete` | -1 | 위와 동일 |
| 관리자 삭제(작성자 탈퇴) | `deleteForModeration` 의 `review.delete()` 분기 | -1 | `review.delete()` 직후 |
| 관리자 재삭제(이미 DELETED) | `deleteForModeration` 의 `isActive()` 가드 → return | 0 | — |
| 본인 재삭제 | `deleteReview` 의 REVIEW_DELETED 이벤트 가드 → 400 | 0 | — |
| 리뷰 수정 | `updateReview` | 0 | — |
| 음식 삭제·비공개 | — | 0 (값 유지) | 노출은 조회 조건이 거른다 |

불변식: 어느 시점이든 커밋된 상태에서 `food.review_count = COUNT(food_review WHERE food_id = food.id AND status = 'ACTIVE')`. 깨지면 백필 문장 재실행으로 복구.

## 조회 계약 (홈 리뷰 인기 레일)

- 대상: `status = ACTIVE`, `content_status = READY`, `review_count > 0`
- 정렬: `review_count DESC, id DESC`
- 크기: `HomeService.MOST_REVIEWED_SIZE`(기존)
- 변경점: 동률 규칙 `max(review.id) DESC` → `food.id DESC`. 응답 DTO·필드 불변.
