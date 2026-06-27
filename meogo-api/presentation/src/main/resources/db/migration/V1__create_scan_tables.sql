-- scan 컨텍스트: 메뉴 스캔 + 항목(판정 스냅샷 포함).
-- 공통 컬럼(status=EntityStatus 소프트삭제, created_at, updated_at)은 BaseEntity(@MappedSuperclass)에서 온다.
CREATE TABLE menu_scan (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    scan_status  VARCHAR(20) NOT NULL,                  -- ScanStatus(도메인): COMPLETED 등
    status       VARCHAR(20) NOT NULL,                  -- EntityStatus: ACTIVE/DELETED
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE scanned_menu_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    scan_id         BIGINT       NOT NULL,
    item_id         INT          NOT NULL,              -- 클라이언트 제공(스캔 내 유일)
    raw_menu_name   VARCHAR(255) NOT NULL,
    bbox_x          DOUBLE       NOT NULL,
    bbox_y          DOUBLE       NOT NULL,
    bbox_width      DOUBLE       NOT NULL,
    bbox_height     DOUBLE       NOT NULL,
    received_order  INT          NOT NULL,
    risk_level      VARCHAR(10)  NOT NULL,              -- mock 판정 스냅샷
    reason          VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL,              -- EntityStatus: ACTIVE/DELETED
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_scan FOREIGN KEY (scan_id) REFERENCES menu_scan(id),
    CONSTRAINT uq_scan_item UNIQUE (scan_id, item_id)
);
