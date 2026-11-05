-- KB-1003 스캔 결과 ↔ 음식 연결 — 스캔 결과 기록(scan_result_log) 한 건에서 음식으로 이어진 항목마다 1행.
-- 미등록 메뉴도 스캔이 만든 미완성 음식 id 로 남겨, 어드민 검수 화면이 "이 음식이 나온 스캔 사진"을 food_id 인덱스로 찾는다.
-- 가산 전용 신규 테이블. scan_result_log 와 같이 로그 성격이라 FK 를 두지 않는다(스캔 경로 잠금과 얽히지 않게).
-- link_source: RECORDED(스캔 시점에 기록) · NAME_BACKFILL(11/4~ 기록분을 이름 정규화 일치로 사후 연결).
-- 같은 기록에 같은 음식은 한 번만(uk) — 백필을 다시 돌려도 멱등.

CREATE TABLE scan_result_food
(
    id                 BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scan_result_log_id BIGINT                    NOT NULL,
    food_id            BIGINT                    NOT NULL,
    matched            TINYINT(1)                NOT NULL,
    scanned_name       VARCHAR(100)              NULL,
    link_source        VARCHAR(16)               NOT NULL,
    status             ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at         DATETIME(6)               NOT NULL,
    updated_at         DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_scan_result_food (scan_result_log_id, food_id),
    INDEX idx_scan_result_food_food (food_id, scan_result_log_id)
);
