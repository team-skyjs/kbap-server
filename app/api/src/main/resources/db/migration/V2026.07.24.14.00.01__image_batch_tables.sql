-- 이미지 배치 메타(KB-226) — 상태의 원천은 OpenAI 가 아니라 이 두 테이블이다.
-- 엔티티 간 JPA 연관관계는 두지 않으므로(참조는 id 값) FK 는 여기서 강제한다(헌법 IV).
-- SUBMITTING: 외부 제출 전 DB 선점(claim-first) — 제출 도중 중단돼도 항목이 PENDING 으로 남아
-- 재제출이 차단되고, 오래된 SUBMITTING 은 회수 틱이 FAILED 로 마감해 복구한다.
CREATE TABLE `image_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `openai_batch_id` varchar(100) NULL,
  `batch_status` enum('SUBMITTING','SUBMITTED','COLLECTED','FAILED') NOT NULL,
  `prompt_version` varchar(20) NOT NULL,
  `model` varchar(50) NOT NULL,
  `submitted_at` datetime(6) NOT NULL,
  `collected_at` datetime(6) NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_image_batch_openai_batch_id` (`openai_batch_id`),
  KEY `idx_image_batch_status` (`batch_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `image_batch_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `food_id` bigint NOT NULL,
  `item_status` enum('PENDING','DONE','FAILED') NOT NULL,
  `file_name` varchar(500) NULL,
  `error_msg` varchar(1000) NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  -- PENDING 인 항목만 food_id 값을 갖는 생성열 — UNIQUE 로 "음식당 진행 중 작업 1개"를 DB 가 강제한다
  -- (동시 제출 경합의 최후 방어선. NULL 은 중복 허용이라 DONE/FAILED 이력은 여러 개 가능).
  `pending_food_id` bigint GENERATED ALWAYS AS (IF(`item_status` = 'PENDING', `food_id`, NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_image_batch_item_pending_food` (`pending_food_id`),
  KEY `idx_image_batch_item_batch` (`batch_id`),
  KEY `idx_image_batch_item_food_status` (`food_id`, `item_status`),
  CONSTRAINT `fk_image_batch_item_batch` FOREIGN KEY (`batch_id`) REFERENCES `image_batch` (`id`),
  CONSTRAINT `fk_image_batch_item_food` FOREIGN KEY (`food_id`) REFERENCES `food` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
