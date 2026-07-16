-- KB-163: 기존 마이그레이션 22개를 스쿼시한 최종 스키마 (도출: 구 마이그레이션 전체 적용 후 mysqldump --no-data)

CREATE SCHEMA IF NOT EXISTS kbap DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE kbap;

CREATE TABLE `avoidance_substance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(40) NOT NULL,
  `korean_name` varchar(100) NOT NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `translations` json NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_avoidance_substance_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `food` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `korean_name` varchar(255) NOT NULL,
  `image_ref` varchar(500) DEFAULT NULL,
  `description` varchar(255) NOT NULL,
  `name_translations` json NOT NULL,
  `description_translations` json NOT NULL,
  `spiciness` int NOT NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `korean_match_key` varchar(255) GENERATED ALWAYS AS (regexp_replace((`korean_name` collate utf8mb4_bin),_utf8mb4'[^가-힣]',_utf8mb4'')) STORED,
  `content_status` enum('INCOMPLETE','READY') NOT NULL DEFAULT 'READY',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_food_korean_name` (`korean_name`),
  KEY `idx_food_korean_match_key` (`korean_match_key`),
  KEY `idx_food_content_status` (`content_status`),
  CONSTRAINT `ck_food_spiciness` CHECK ((`spiciness` between 0 and 10))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider` enum('GOOGLE','APPLE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider_uid` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `nickname` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `profile` json NOT NULL,
  `member_status` enum('ACTIVE','SUSPENDED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE',
  `onboarding_completed` tinyint(1) NOT NULL DEFAULT '0',
  `status` enum('ACTIVE','DELETED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `scan_count` int NOT NULL DEFAULT '0',
  `review_count` int NOT NULL DEFAULT '0',
  `unique_reviewed_food_count` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_provider_uid` (`provider`,`provider_uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `bookmark` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `food_id` bigint NOT NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_bookmark_member` (`member_id`),
  KEY `fk_bookmark_food` (`food_id`),
  CONSTRAINT `fk_bookmark_food` FOREIGN KEY (`food_id`) REFERENCES `food` (`id`),
  CONSTRAINT `fk_bookmark_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `food_avoidance_substance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `food_id` bigint NOT NULL,
  `substance_code` varchar(40) NOT NULL,
  `inclusion_percent` int NOT NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_food_avoidance_substance` (`food_id`,`substance_code`),
  KEY `fk_fas_substance` (`substance_code`),
  CONSTRAINT `fk_fas_food` FOREIGN KEY (`food_id`) REFERENCES `food` (`id`),
  CONSTRAINT `fk_fas_substance` FOREIGN KEY (`substance_code`) REFERENCES `avoidance_substance` (`code`),
  CONSTRAINT `ck_fas_inclusion_percent` CHECK ((`inclusion_percent` between 1 and 100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `scan_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `image_path` varchar(512) NOT NULL,
  `menu_name` varchar(100) NOT NULL,
  `korean_name` varchar(100) NOT NULL,
  `price` int DEFAULT NULL,
  `food_id` bigint DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_scan_history_recent` (`member_id`,`created_at`),
  KEY `fk_scan_history_food` (`food_id`),
  CONSTRAINT `fk_scan_history_food` FOREIGN KEY (`food_id`) REFERENCES `food` (`id`),
  CONSTRAINT `fk_scan_history_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `uploaded_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `object_path` varchar(512) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `size_bytes` bigint NOT NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uploaded_image_path` (`object_path`),
  KEY `idx_uploaded_image_member` (`member_id`),
  CONSTRAINT `fk_uploaded_image_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
