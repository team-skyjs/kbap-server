-- KB-1012 메뉴 음식 → 가공식품 대표 상품 연결 (예진 11/6). 메뉴판의 콜라·사이다 같은 음료를 누끼 승인된 가공식품 하나로 보여 준다.
-- 가산 전용 신규 테이블. 음식당 1행(uk) — 재지정은 같은 행의 상품 id 를 바꾸고, 해제는 status=DELETED, 다시 연결하면 그 행을 되살린다.
-- 여러 음식이 같은 상품을 가리킬 수 있다. 위험도·재료 판정은 음식 자체를 쓰고 연결은 표시(이름·누끼 사진)에만 쓴다.

CREATE TABLE food_processed_link
(
    id                        BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    food_id                   BIGINT                    NOT NULL,
    processed_food_product_id BIGINT                    NOT NULL,
    linked_by                 BIGINT                    NULL,
    status                    ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at                DATETIME(6)               NOT NULL,
    updated_at                DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_food_processed_link_food (food_id),
    KEY idx_food_processed_link_product (processed_food_product_id),
    CONSTRAINT fk_food_processed_link_food FOREIGN KEY (food_id) REFERENCES food (id),
    CONSTRAINT fk_food_processed_link_product FOREIGN KEY (processed_food_product_id) REFERENCES processed_food_product (id),
    CONSTRAINT fk_food_processed_link_admin FOREIGN KEY (linked_by) REFERENCES admin_account (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
