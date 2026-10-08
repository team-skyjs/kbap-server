-- KB-728: 가입 회원에게 1회 받는 프로필 설문(짝 A — 스키마 선행). 가산만: 새 표 하나, 기존 표·값을 바꾸지 않는다.
-- 이 파일은 짝 B(코드) PR 에 byte 단위로 같게 동봉한다 — Flyway 체크섬은 주석까지 포함한다.
--
-- 회원당 1행(uk_member_survey_member). 다시 답하면 같은 행을 덮는다. id 는 모든 엔티티가 공유하는 BaseEntity 의 키라 두고,
-- 회원 1행 제약은 member_id 의 UNIQUE 로 건다.
-- 응답값은 VARCHAR(32) 코드 — 선택지와 검증은 코드(enum)가 정한다. trip_timing·trip_duration 은 상황(situation)에 따라
-- 답이 없는 경우가 있어 NULL 을 허용한다. food_affinity 는 1~5 점. survey_version 은 문항이 바뀔 때 올린다(기본 1).
-- answered_at 은 마지막으로 답한 시각이다.
-- FK 에 ON DELETE 를 두지 않는다(소프트 삭제 구조). 탈퇴 파기(KB-570)는 코드가 이 행을 지운다.
-- 엔진·문자셋·콜레이션은 member 와 같게 명시한다.
CREATE TABLE member_survey
(
    id             BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id      BIGINT                    NOT NULL,
    age_band       VARCHAR(32)               NOT NULL,
    gender         VARCHAR(32)               NOT NULL,
    acquisition    VARCHAR(32)               NOT NULL,
    situation      VARCHAR(32)               NOT NULL,
    trip_timing    VARCHAR(32)               NULL,
    trip_duration  VARCHAR(32)               NULL,
    purpose        VARCHAR(32)               NOT NULL,
    food_affinity  TINYINT                   NOT NULL,
    survey_version SMALLINT                  NOT NULL DEFAULT 1,
    answered_at    DATETIME(6)               NOT NULL,
    status         ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(6)               NOT NULL,
    updated_at     DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_member_survey_member (member_id),
    CONSTRAINT fk_member_survey_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT chk_member_survey_food_affinity CHECK (food_affinity BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
