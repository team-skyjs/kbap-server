-- 리뷰 좋아요(KB-271): 회원-리뷰 관계. 쌍당 1행 — 취소는 소프트삭제(status=DELETED), 재등록은 부활(upsert).
-- 조회용 별도 인덱스 없음: 쌍 단건·리뷰별 집계·회원 좋아요 여부 모두 uk 로 커버(member_id 단독 조회는 범위 밖).
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
