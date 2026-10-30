-- KB-939(R-171): 가공식품 앱 인기 신호 원장. 회원·상품·신호 종류·일자(KST)만 남긴다(개인정보 최소).
-- signal_type: VIEW(가공식품 상세 조회, 회원만) · SCAN_MATCH(이름 스캔 확정). 리뷰는 food_review 를 직접 읽으므로 여기 적재하지 않는다. 저장(SAVE)은 기능이 생길 때 값을 더한다.
-- UNIQUE(상품, 회원, 신호, 일자) 로 "사용자·상품·신호·일 1회" 를 DB 가 보장한다(INSERT IGNORE).
-- FK 는 두지 않는다(food_view_log 선례 — 원본이 지워져도 이력은 남고, 적재가 원본 삭제와 경합하지 않는다).
-- 인덱스 (signal_date, processed_food_id, signal_type, member_id): 매일 밤 최근 90일 창 → 상품·신호·회원별 집계를 인덱스만으로 읽는다(커버링).
-- 집계는 status 를 걸지 않는다 — 이 원장은 소프트 삭제하지 않는다(DELETED 를 쓰는 경로가 없다).
-- 가산만: 새 테이블. 기존 테이블·값은 건드리지 않는다.
CREATE TABLE processed_food_signal
(
    id                BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    processed_food_id BIGINT                    NOT NULL,
    member_id         BIGINT                    NOT NULL,
    signal_type       VARCHAR(16)               NOT NULL,
    signal_date       DATE                      NOT NULL,
    status            ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at        DATETIME(6)               NOT NULL,
    updated_at        DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_processed_food_signal_daily (processed_food_id, member_id, signal_type, signal_date),
    INDEX idx_processed_food_signal_date_product (signal_date, processed_food_id, signal_type, member_id)
);
