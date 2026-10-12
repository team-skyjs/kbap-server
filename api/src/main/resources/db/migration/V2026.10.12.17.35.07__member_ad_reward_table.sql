-- KB-740: 보상형 광고 적립 이력(짝 A — 스키마 선행). 가산만: 새 표 하나, 기존 표·값을 바꾸지 않는다.
-- 이 파일은 짝 B(코드) PR 에 byte 단위로 같게 동봉한다 — Flyway 체크섬은 주석까지 포함한다.
--
-- 한 행 = 광고 SDK 가 보상 완료를 신고한 1회. reward_key 는 SDK 콜백 고유값이라 UNIQUE 로 멱등을 건다(같은 키 재요청은 행 추가 없음).
-- 적립 수는 member 컬럼이 아니라 이 표의 COUNT 로 센다 — 멱등 판정·일일 상한·누적 수가 전부 같은 행을 보게 해 정본을 하나로 둔다.
-- (member_id, created_at) 인덱스가 누적 COUNT 와 최근 24시간 상한 조회를 받친다.
-- FK 에 ON DELETE 를 두지 않는다(소프트 삭제 구조). 탈퇴 회원의 행은 member 가 DELETED 라 조회되지 않는다.
CREATE TABLE member_ad_reward
(
    id         BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id  BIGINT                    NOT NULL,
    reward_key VARCHAR(128)              NOT NULL,
    ad_unit_id VARCHAR(100)              NOT NULL,
    status     ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)               NOT NULL,
    updated_at DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_member_ad_reward_key (reward_key),
    KEY idx_member_ad_reward_member_created (member_id, created_at),
    CONSTRAINT fk_member_ad_reward_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
