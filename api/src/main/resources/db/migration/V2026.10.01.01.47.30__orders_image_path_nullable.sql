-- KB-417: 스캔 사진 없는 주문을 저장할 수 있게 orders.image_path 를 NULL 허용으로 바꾼다.
-- uq_orders_image_path 는 그대로 둔다 — MySQL UNIQUE 는 NULL 을 여러 개 허용하므로
-- 사진 있는 주문의 "스캔 1회당 주문 1회" 보장은 유지된다.
-- NOT NULL -> NULL 은 INSTANT 가 아니라 INPLACE(테이블 재구성) + 동시 DML 허용이다.
-- ALGORITHM/LOCK 을 명시해, 지원되지 않는 조건이면 COPY·강한 잠금으로 떨어지지 않고 실패하게 한다.
ALTER TABLE orders
    MODIFY COLUMN image_path VARCHAR(512) NULL,
    ALGORITHM = INPLACE,
    LOCK = NONE;
