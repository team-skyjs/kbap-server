# Data Model: 리뷰 CRUD (KB-128)

## 엔티티: Review (`common.domain.review.model.Review`)

`BaseEntity` 상속(id IDENTITY·status 소프트삭제·createdAt·updatedAt — `@SQLRestriction("status = 'ACTIVE'")` 상시 적용). JPA 연관관계 없음 — 참조는 전부 id 값.

| 필드 | 타입 | 컬럼 | 제약 | 비고 |
|------|------|------|------|------|
| memberId | `Long` | `member_id` | NOT NULL, FK→member(id) | 작성자. ON DELETE 없음(소프트삭제 구조) |
| foodId | `Long` | `food_id` | NOT NULL, FK→food(id) | 대상 음식 |
| rating | `Int` | `rating` TINYINT | NOT NULL, 1~5 | 도메인 불변: `require(rating in 1..5)` |
| content | `String?` | `content` VARCHAR(1000) | NULL | 도메인 불변: `length <= 1000` |
| imageRefs | `List<String>?` | `image_refs` JSON | NULL | `@JdbcTypeCode(SqlTypes.JSON)`, 도메인 불변: `size <= 3`. NULL=사진 없음 |
| authorCountryCode | `String?` | `author_country_code` VARCHAR(10) | NULL | 작성 시점 국적 스냅샷. NULL=국적 미보유. 수정 시 갱신 안 함 |

### 도메인 메서드 (엔티티 내장)

- `init` — rating 범위·content 길이·imageRefs 수 불변 검증(`require`)
- `update(rating, content, imageRefs)` — 본인 확인 후 호출되는 상태 변경(불변 재검증). authorCountryCode 는 불변
- `isOwnedBy(memberId: Long): Boolean` — 수정/삭제 권한 판정
- 삭제는 `BaseEntity.delete()`(status=DELETED) — 목록·집계 자동 제외

## Flyway: `V2026.07.29.HH.mm.ss__food_review_table.sql` (PR1, 생성 시각으로 채번)

```sql
CREATE TABLE `food_review` (
    `id`                  bigint       NOT NULL AUTO_INCREMENT,
    `member_id`           bigint       NOT NULL,
    `food_id`             bigint       NOT NULL,
    `rating`              tinyint      NOT NULL,
    `content`             varchar(1000)         NULL,
    `image_refs`          json                  NULL,
    `author_country_code` varchar(10)           NULL,
    `status`              enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`          datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`          datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    KEY `idx_food_review_food_recent` (`food_id`, `id` DESC),
    KEY `idx_food_review_member_recent` (`member_id`, `id` DESC),
    KEY `idx_food_review_food_country` (`food_id`, `author_country_code`),
    CONSTRAINT `fk_food_review_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
    CONSTRAINT `fk_food_review_food` FOREIGN KEY (`food_id`) REFERENCES `food` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- 테이블명 **`food_review`** — 단수 관례(`bookmark`·`member`) + 앱 리뷰 등과의 혼동을 피하는 도메인 접두. 엔티티·패키지명은 리뷰 컨텍스트 안이라 `Review` 유지.
- 인덱스 용도: `idx_food_review_food_recent`=음식별 최신순 keyset, `idx_food_review_member_recent`=내 리뷰 keyset, `idx_food_review_food_country`=국적 필터 목록·같은 국적 AVG(세컨더리 인덱스에 PK 암묵 포함 → id 정렬 커버).
- member 측 컬럼(`review_count`·`unique_reviewed_food_count`)은 init_schema 기존재 — 추가 마이그레이션 없음.

## 리포지토리: ReviewJpaRepository (`common.domain.review`)

```kotlin
interface ReviewJpaRepository : JpaRepository<Review, Long> {
    // 음식별 keyset (countryCode null=전체) — order by r.id desc, PageRequest(0, size+1)
    @Query("""select r from Review r
              where r.foodId = :foodId
                and (:countryCode is null or r.authorCountryCode = :countryCode)
                and (:cursor is null or r.id < :cursor)
              order by r.id desc""")
    fun findFoodReviewPage(foodId: Long, countryCode: String?, cursor: Long?, pageable: Pageable): List<Review>

    // 내 리뷰 keyset
    @Query("""select r from Review r
              where r.memberId = :memberId and (:cursor is null or r.id < :cursor)
              order by r.id desc""")
    fun findMemberReviewPage(memberId: Long, cursor: Long?, pageable: Pageable): List<Review>

    // 평점 집계 (avg null=리뷰 0건). countryCode null 이면 전체
    @Query("""select avg(r.rating), count(r) from Review r
              where r.foodId = :foodId
                and (:countryCode is null or r.authorCountryCode = :countryCode)""")
    fun aggregateRating(foodId: Long, countryCode: String?): List<Array<Any?>>  // 구현 시 projection 인터페이스로 대체 가능

    // 첫/마지막 리뷰 판정 (랭킹 고유 음식 수)
    fun countByMemberIdAndFoodId(memberId: Long, foodId: Long): Long
}
```

`@SQLRestriction` 이 모든 쿼리에 `status='ACTIVE'` 를 자동 적용 — 별도 status 조건 금지(컨벤션).

## MemberJpaRepository 추가 (PR2)

```kotlin
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""update Member m
          set m.reviewCount = m.reviewCount + 1,
              m.uniqueReviewedFoodCount = m.uniqueReviewedFoodCount + :uniqueDelta
          where m.id = :memberId and m.memberStatus = ACTIVE and m.status = ACTIVE""")
fun increaseReviewCounts(memberId: Long, uniqueDelta: Int): Int   // uniqueDelta ∈ {0,1}

@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""update Member m
          set m.reviewCount = m.reviewCount - 1,
              m.uniqueReviewedFoodCount = m.uniqueReviewedFoodCount - :uniqueDelta
          where m.id = :memberId and m.reviewCount > 0
            and m.uniqueReviewedFoodCount >= :uniqueDelta
            and m.memberStatus = ACTIVE and m.status = ACTIVE""")
fun decreaseReviewCounts(memberId: Long, uniqueDelta: Int): Int
```

공개 창구는 `MemberService`(도메인 서비스): `increaseReviewCounts(memberId, firstReviewOfFood: Boolean)` / `decreaseReviewCounts(memberId, lastReviewOfFood: Boolean)` — 갱신 0건이면 `MEMBER_NOT_FOUND`(increaseScanCount 선례).

## 상태 전이

```
작성: (없음) → ACTIVE      — review_count+1, count(member,food)==1 이면 unique+1
수정: ACTIVE → ACTIVE      — rating/content/imageRefs 만, 카운트·스냅샷 불변
삭제: ACTIVE → DELETED     — review_count-1, count(member,food)==0 이면 unique-1
```

트랜잭션 경계: `com.kbap.api.review.ReviewService` public 메서드의 명시 `@Transactional`(조회는 readOnly) — 리뷰 저장/삭제와 카운트 증감·첫/마지막 판정 count 쿼리를 한 트랜잭션으로 묶는다. 외부 시스템 호출 없음(이미지 검증도 DB 조회).

## ErrorCode 추가 (PR2)

| code | enum | status | 용도 |
|------|------|--------|------|
| REVIEW-001 | REVIEW_NOT_FOUND | 400 | 대상 리뷰 없음/이미 삭제 (not-found=400 관례: MEMBER-003·FOOD-001) |
| REVIEW-002 | REVIEW_FORBIDDEN | 403 | 타인 리뷰 수정/삭제 (403 선례: AUTH-008) |
| REVIEW-003 | REVIEW_IMAGE_NOT_VERIFIED | 400 | 본인이 업로드하지 않았거나 미완료 이미지 경로 |

`FOOD-001`(음식 없음)·`FOOD-002`(INVALID_CURSOR)·`MEMBER-003` 은 재사용.
