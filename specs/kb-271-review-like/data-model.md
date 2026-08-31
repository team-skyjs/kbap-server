# Data Model: 리뷰 좋아요 (kb-271)

## 신규 테이블 `review_like`

`member_block`(V2026.08.01.05.17.33) 을 그대로 미러링한 관계 테이블. 쌍당 1행 — 취소는 소프트삭제, 재등록은 부활.

```sql
CREATE TABLE `review_like` (
    `id`         bigint      NOT NULL AUTO_INCREMENT,
    `review_id`  bigint      NOT NULL,
    `member_id`  bigint      NOT NULL,
    `status`     enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_review_like_pair` (`review_id`, `member_id`),
    CONSTRAINT `fk_review_like_review` FOREIGN KEY (`review_id`) REFERENCES `food_review` (`id`),
    CONSTRAINT `fk_review_like_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- **인덱스**: 조회 3종 모두 uk 로 커버 — 쌍 단건(review_id+member_id 완전 일치), 리뷰별 집계(`review_id in (...) group by review_id` — 좌측 접두), 내 좋아요 여부(`review_id in (...) and member_id = ?` — 복합키 전체). member_id 단독 조회(내가 좋아요한 전체 목록)는 이번 범위 밖이라 별도 인덱스를 만들지 않는다.
- **집계 시 status 필터**: 엔티티 경유 JPQL 은 `@SQLRestriction` 이 자동으로 `status='ACTIVE'` 를 적용하므로 쿼리에 status 조건을 달지 않는다(컨벤션).
- **FK**: ON DELETE 없음(소프트 삭제 구조) — 스키마가 참조 무결성만 강제.

## 엔티티 `ReviewLike`

`com.kbap.common.domain.review.model.ReviewLike` — review 컨텍스트 소유, `BaseEntity` 상속.

| 필드 | 타입 | 컬럼 | 비고 |
|------|------|------|------|
| (상속) id·status·createdAt·updatedAt | — | BaseEntity 공통 | 자체 id·시각 금지 |
| reviewId | `Long` | `review_id` | id 값 참조 — JPA 연관관계 금지 |
| memberId | `Long` | `member_id` | id 값 참조 |

도메인 메서드 없음(순수 관계 사실). 상태 전이는 `BaseEntity.delete()`(취소)와 리포지토리 upsert(등록/부활)가 전부다.

## 리포지토리 `ReviewLikeJpaRepository`

`com.kbap.common.domain.review.ReviewLikeJpaRepository` — public, `JpaRepository<ReviewLike, Long>`.

| 메서드 | 형태 | 용도 |
|--------|------|------|
| `upsertActive(reviewId, memberId)` | `@Modifying` native `INSERT ... ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=NOW(6)` | 등록 — 신규·중복·재등록(부활) 원자 처리 (member_block `upsertActive` 미러) |
| `findByReviewIdAndMemberId(reviewId, memberId)` | 파생 쿼리 | 취소 대상 조회(→ `delete()` 소프트삭제) |
| `countByReviewIds(reviewIds)` | JPQL `select rl.reviewId, count(rl) ... where rl.reviewId in :reviewIds group by rl.reviewId` → projection | 목록 좋아요 수 배치 집계 |
| `findLikedReviewIds(memberId, reviewIds)` | JPQL `select rl.reviewId ... where rl.memberId = :memberId and rl.reviewId in :reviewIds` | 조회 회원의 좋아요 여부 배치 로드 |

## 상태 전이

```text
(행 없음) --등록 upsert--> ACTIVE --취소 delete()--> DELETED --재등록 upsert--> ACTIVE
                ACTIVE --중복 등록 upsert--> ACTIVE (변화 없음, 성공)
                (행 없음/DELETED) --취소--> no-op (성공)
```

## 검증 규칙 (요구사항 매핑)

| 규칙 | 강제 지점 |
|------|-----------|
| 리뷰 존재(활성) 시에만 등록 (FR-001·006) | `ReviewService.likeReview` — `reviewRepository.findById` orElseThrow `REVIEW_NOT_FOUND` (`@SQLRestriction` 이 삭제 리뷰 자동 제외) |
| 쌍당 최대 1개, 동시 요청 포함 (FR-002) | DB `uk_review_like_pair` + upsert 원자 문장 |
| 등록/취소 멱등 (FR-003·004) | upsert ON DUPLICATE(등록) · null-safe 소프트삭제(취소) |
| 인증 회원만 (FR-009) | 컨트롤러 `@AuthMemberId` (기존 보호 경로 규약) |
| 삭제된 리뷰의 좋아요 비노출 (엣지) | 목록 쿼리가 활성 리뷰만 반환 → 그 리뷰의 좋아요는 조회 대상에서 자연 제외 |

## 기존 모델 변경

- `ReviewResponse`(api DTO): `likeCount: Long`·`likedByMe: Boolean` 필드 추가, `from(...)` 파라미터 확장. 도메인 엔티티 `Review` 는 무변경.
- ArchUnit `ModuleBoundaryTest` 도메인 허용 맵: 변경 없음 — `ReviewLike` 는 review 컨텍스트 내부이고 member 참조는 id 값이다.
