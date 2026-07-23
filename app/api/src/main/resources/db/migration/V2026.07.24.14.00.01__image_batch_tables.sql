-- 이미지 배치 메타(KB-226) — 상태의 원천은 OpenAI 가 아니라 이 두 테이블이다.
-- 엔티티 간 JPA 연관관계는 두지 않으므로(참조는 id 값) FK 는 여기서 강제한다(헌법 IV).
CREATE TABLE `image_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `openai_batch_id` varchar(100) NOT NULL,
  `batch_status` enum('SUBMITTED','COLLECTED','FAILED') NOT NULL,
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
  PRIMARY KEY (`id`),
  KEY `idx_image_batch_item_batch` (`batch_id`),
  KEY `idx_image_batch_item_food_status` (`food_id`, `item_status`),
  CONSTRAINT `fk_image_batch_item_batch` FOREIGN KEY (`batch_id`) REFERENCES `image_batch` (`id`),
  CONSTRAINT `fk_image_batch_item_food` FOREIGN KEY (`food_id`) REFERENCES `food` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
