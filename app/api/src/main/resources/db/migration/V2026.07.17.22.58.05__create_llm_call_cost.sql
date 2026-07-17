CREATE TABLE `llm_call_cost` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model_name` varchar(100) NOT NULL,
  `input_tokens` bigint NOT NULL,
  `output_tokens` bigint NOT NULL,
  `cost_usd` decimal(12,6) NOT NULL,
  `cost_krw` decimal(14,2) NOT NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_llm_call_cost_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
