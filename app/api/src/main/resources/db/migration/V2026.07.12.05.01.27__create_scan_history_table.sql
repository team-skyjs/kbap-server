-- KB-111: 회원별 스캔 이력. READY 음식에 매칭된 스캔만 음식당 1건 적재하고,
-- 홈화면 "최근 스캔" 은 (member_id, created_at) 인덱스로 최신순 조회한다.
-- 공통 컬럼(status=EntityStatus 소프트삭제, created_at, updated_at)은 BaseEntity(@MappedSuperclass)에서 온다.
CREATE TABLE scan_history (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    food_id    BIGINT      NOT NULL,
    status     VARCHAR(20) NOT NULL,                    -- EntityStatus: ACTIVE/DELETED
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_scan_history_recent (member_id, created_at),
    CONSTRAINT fk_scan_history_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_scan_history_food FOREIGN KEY (food_id) REFERENCES food (id)
);
