-- KB-375: 관리자 조작 감사 이력. 대상 삭제 후에도 이력이 남아야 하므로 FK 를 두지 않는다.
CREATE TABLE `admin_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `admin_account_id` bigint NOT NULL,
  `action` varchar(50) NOT NULL,
  `target_type` varchar(30) NOT NULL,
  `target_id` bigint DEFAULT NULL,
  `before_json` json DEFAULT NULL,
  `after_json` json DEFAULT NULL,
  `note` varchar(500) DEFAULT NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_audit_target` (`target_type`, `target_id`, `id`),
  KEY `idx_admin_audit_admin` (`admin_account_id`, `id`),
  KEY `idx_admin_audit_action` (`action`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
