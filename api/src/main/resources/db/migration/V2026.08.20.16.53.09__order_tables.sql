-- KB-337: 주문 내역·주문 음식 이력. 스캔 결과에서 고른 메뉴를 스냅샷(이름·가격)으로 저장한다.
-- image_path UNIQUE = "스캔 1회당 주문 1회" 를 DB 가 원자적으로 강제(더블탭 경합 포함).
-- 좌표는 저장만 하고 API 응답에 노출하지 않는다. road_address 는 Google 역지오코딩 결과(실패 시 NULL).
-- food_id 는 FK 를 걸지 않는 논리 참조 — 주문 내역은 음식 마스터 변경과 무관하게 스냅샷으로 유지된다.
CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `image_path` varchar(512) NOT NULL,
  `latitude` decimal(10,7) NULL,
  `longitude` decimal(10,7) NULL,
  `road_address` varchar(200) NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_orders_image_path` (`image_path`),
  KEY `idx_orders_recent` (`member_id`, `created_at`),
  CONSTRAINT `fk_orders_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `food_id` bigint NOT NULL,
  `menu_name` varchar(100) NOT NULL,
  `quantity` int NOT NULL,
  `price` int NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_order_item_order` (`order_id`),
  CONSTRAINT `fk_order_item_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
