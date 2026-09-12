-- 음식 이미지 갤러리(음식당 여러 장, 대표 1장) 토대 테이블 — P1 은 데이터 축적만, API 노출은 후속.
-- 음식당 primary 1개는 생성 컬럼 유니크로 DB 가 보장한다(image_batch_item.pending_food_id 선례).
CREATE TABLE `food_image`
(
    `id`              bigint            NOT NULL AUTO_INCREMENT,
    `food_id`         bigint            NOT NULL,
    `image_key`       varchar(500)      NOT NULL,
    `is_primary`      tinyint(1)        NOT NULL DEFAULT 0,
    `source`          enum ('GENERATED') NOT NULL DEFAULT 'GENERATED',
    `sort_order`      int               NOT NULL DEFAULT 0,
    `status`          enum ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`      datetime(6)       NOT NULL,
    `updated_at`      datetime(6)       NOT NULL,
    `primary_food_id` bigint AS (IF(`is_primary` = 1, `food_id`, NULL)) STORED,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_food_image_primary` (`primary_food_id`),
    KEY `idx_food_image_food` (`food_id`, `status`),
    CONSTRAINT `fk_food_image_food` FOREIGN KEY (`food_id`) REFERENCES `food` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
