-- KB-90: 미등록 표준명(폴백 시 원문) 조사 대기열. standard_name UNIQUE 로 dedup.
-- 공통 컬럼(status=EntityStatus, created_at, updated_at)은 BaseEntity 규약과 동일.
CREATE TABLE pending_menus (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    standard_name VARCHAR(100) NOT NULL,
    queue_status  VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pending_menus_standard_name (standard_name)
);
