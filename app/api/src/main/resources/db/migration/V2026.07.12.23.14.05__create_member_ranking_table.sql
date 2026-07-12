-- KB-123: 회원 랭킹 활동 카운터. 랭킹 점수의 원천 카운트를 회원당 1행으로 누적한다.
-- 스캔 횟수는 "메뉴판 1장 = 1회" 단위다(scan_history 는 매칭된 음식마다 행이 생기므로 횟수 집계에 쓸 수 없다).
-- 카운트업은 INSERT ... ON DUPLICATE KEY UPDATE 로 원자적으로 처리하므로 uk_member_ranking_member 가 필수다.
-- 리뷰 수·고유 음식 수는 리뷰 도메인 도입 시 컬럼을 추가한다(현재 점수 계산에서 0).
-- 공통 컬럼(status=EntityStatus 소프트삭제, created_at, updated_at)은 BaseEntity(@MappedSuperclass)에서 온다.
CREATE TABLE member_ranking (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    scan_count INT         NOT NULL DEFAULT 0,
    status     VARCHAR(20) NOT NULL,                    -- EntityStatus: ACTIVE/DELETED
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_ranking_member UNIQUE (member_id),
    CONSTRAINT fk_member_ranking_member FOREIGN KEY (member_id) REFERENCES member (id)
);
