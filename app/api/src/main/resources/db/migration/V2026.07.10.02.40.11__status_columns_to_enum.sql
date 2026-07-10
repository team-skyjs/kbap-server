-- 상태 컬럼의 후보값을 DB 스키마에 고정한다.
-- VARCHAR 이면 애플리케이션이 오타('ACTIV', 'REDY')를 그대로 적재할 수 있고, 이후 @SQLRestriction 의
-- status = 'ACTIVE' 필터에 걸리지 않아 행이 조용히 사라진 것처럼 보인다.
-- MySQL ENUM 은 STRICT_TRANS_TABLES 에서 정의되지 않은 값을 ERROR 1265 로 거부한다.
--
-- 값 추가 시 주의: 목록 "끝"에 append 하면 MySQL 8 은 메타데이터만 바꾼다(ALGORITHM=INSTANT).
-- 중간 삽입·순서 변경은 테이블 재작성이므로 하지 않는다.

ALTER TABLE avoidance_substance
    MODIFY status ENUM ('ACTIVE', 'DELETED') NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE food
    MODIFY status ENUM ('ACTIVE', 'DELETED') NOT NULL,
    MODIFY content_status ENUM ('INCOMPLETE', 'READY') NOT NULL DEFAULT 'READY';

ALTER TABLE food_avoidance_substance
    MODIFY status ENUM ('ACTIVE', 'DELETED') NOT NULL;

ALTER TABLE members
    MODIFY status ENUM ('ACTIVE', 'DELETED') NOT NULL,
    MODIFY onboarding_status ENUM ('PENDING', 'COMPLETED') NOT NULL;

ALTER TABLE member_social_identities
    MODIFY status ENUM ('ACTIVE', 'DELETED') NOT NULL,
    MODIFY provider ENUM ('GOOGLE', 'APPLE') NOT NULL;
