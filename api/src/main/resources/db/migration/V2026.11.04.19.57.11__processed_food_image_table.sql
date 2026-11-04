-- KB-965: 가공식품 상품당 사진 여러 장(음식 food_image 갤러리와 같은 구조). 기존 processed_food_product.image_url(HACCP 원본)·cutout_image_url(누끼)은 그대로 두고, 추가 사진만 여기 둔다.
-- image_url = 공개(CDN) URL 전체(cutout_image_url 과 같은 형식 — 어드민 presign·confirm 으로만 채운다).
-- kind: CUTOUT·FRONT·TOP·LABEL·OTHER. ENUM 이 아니라 VARCHAR 로 둔다 — 종류가 늘어도 MODIFY 없이 값만 더한다(cutout_review_status 선례).
-- sort_order: 상품 안에서 오름차순 표시 순서. 앱 응답은 누끼 대표를 먼저, 그다음 sort_order 다(정렬 규칙은 코드 소유).
-- FK 는 food_image 선례처럼 둔다 — 원장(log)이 아니라 상품에 딸린 데이터이고, 상품은 소프트 삭제만 한다.
-- 인덱스 (processed_food_id, status, sort_order): 상세 조회가 상품별 ACTIVE 사진을 순서대로 읽는다.
-- 가산만: 새 테이블. 기존 테이블·값은 건드리지 않는다.
-- lock_wait_timeout 10초: FK 가 있는 CREATE 는 부모 processed_food_product 의 메타데이터 잠금을 잡는다. 부모를 읽는 긴 트랜잭션이 있으면 CREATE 가 기다리는 사이 뒤의 읽기·쓰기까지 줄 선다 — 10초 안에 못 잡으면 실패시키고 재시도한다.

SET SESSION lock_wait_timeout = 10;

CREATE TABLE processed_food_image
(
    id                BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    processed_food_id BIGINT                    NOT NULL,
    image_url         VARCHAR(500)              NOT NULL,
    kind              VARCHAR(16)               NOT NULL,
    sort_order        INT                       NOT NULL DEFAULT 0,
    status            ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at        DATETIME(6)               NOT NULL,
    updated_at        DATETIME(6)               NOT NULL,
    INDEX idx_processed_food_image_product (processed_food_id, status, sort_order),
    CONSTRAINT fk_processed_food_image_product FOREIGN KEY (processed_food_id) REFERENCES processed_food_product (id)
);

SET SESSION lock_wait_timeout = DEFAULT;
