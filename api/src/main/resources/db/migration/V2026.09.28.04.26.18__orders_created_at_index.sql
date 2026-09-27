-- KB-469: 리뷰 리마인더 배치가 5분마다 orders 를 created_at 창(1~25시간 전)으로 스캔한다.
-- 기존 인덱스는 (member_id, id) 뿐이라 주문이 누적될수록 PK 순 스캔 비용이 커진다 — 시간 범위 seek 용 인덱스 추가.
ALTER TABLE `orders` ADD KEY `idx_orders_created_at` (`created_at`);
