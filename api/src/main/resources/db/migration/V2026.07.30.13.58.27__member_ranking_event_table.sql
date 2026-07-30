-- 랭킹 이력 원장(KB-128): 리뷰가 랭킹 카운트에 준 영향을 은행 이체 내역처럼 행으로 남긴다.
-- member 카운트 컬럼 = 잔액, 이 테이블 = 거래 내역 — 롤백은 반대 부호의 보상 이벤트로 수행한다.
-- uq(review_id, event) 로 한 리뷰는 랭킹에 최대 1회 반영·1회 차감(멱등)임을 DB 가 보장한다.
CREATE TABLE `member_ranking_event` (
    `id`                      bigint      NOT NULL AUTO_INCREMENT,
    `member_id`               bigint      NOT NULL,
    `review_id`               bigint      NOT NULL,
    `event`                   enum('REVIEW_CREATED','REVIEW_DELETED') NOT NULL,
    `review_count_delta`      tinyint     NOT NULL,
    `unique_food_count_delta` tinyint     NOT NULL,
    `status`                  enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`              datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`              datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_member_ranking_event` (`review_id`, `event`),
    KEY `idx_member_ranking_event_member` (`member_id`, `id`),
    CONSTRAINT `fk_member_ranking_event_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
    CONSTRAINT `fk_member_ranking_event_review` FOREIGN KEY (`review_id`) REFERENCES `food_review` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
