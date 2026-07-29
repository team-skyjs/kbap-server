-- 음식 리뷰(KB-128): 별점(1~5)·본문(<=1000자)·사진(<=3장, JSON)·작성 시점 국적 스냅샷.
-- 테이블명은 앱 리뷰 등과의 혼동을 피해 food_review 로 한정한다.
-- author_country_code 는 member 조인 없이 같은 국적 평점·필터를 계산하기 위한 불변 스냅샷(NULL=국적 미보유).
-- 사진은 리뷰와 생명주기가 같아 별도 테이블 없이 JSON 컬럼(image_refs)으로 보관한다.
CREATE TABLE `food_review` (
    `id`                  bigint        NOT NULL AUTO_INCREMENT,
    `member_id`           bigint        NOT NULL,
    `food_id`             bigint        NOT NULL,
    `rating`              tinyint       NOT NULL,
    `content`             varchar(1000)     NULL,
    `image_refs`          json              NULL,
    `author_country_code` varchar(10)       NULL,
    `status`              enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`          datetime(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`          datetime(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    KEY `idx_food_review_food_recent` (`food_id`, `id` DESC),
    KEY `idx_food_review_member_recent` (`member_id`, `id` DESC),
    KEY `idx_food_review_food_country` (`food_id`, `author_country_code`),
    CONSTRAINT `fk_food_review_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
    CONSTRAINT `fk_food_review_food` FOREIGN KEY (`food_id`) REFERENCES `food` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
