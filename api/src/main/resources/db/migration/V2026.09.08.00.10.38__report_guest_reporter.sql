-- KB-459: 게스트 신고 허용. reporter_member_id 를 NULL 허용으로 완화하고 신고 요청이 발생한 앱 설치를
-- 가리키는 reporter_installation_id 를 추가한다(ADD/MODIFY only).
--
-- 컬럼 의미: reporter_member_id = 신고 당시 인증된 회원, reporter_installation_id = 신고가 발생한 앱 설치.
-- 신규 신고는 회원이면 (M, D), 게스트면 (NULL, D) 로 둘 다 저장한다. 기존 회원 행은 설치 정보를 복원할
-- 수 없으므로 (M, NULL) 그대로 보존한다 — 그래서 컬럼을 NOT NULL 로 승격하지 않는다.
--
-- 중복 신고 차단은 독립 유니크 두 개로 "회원 키 충돌 OR 설치 키 충돌"을 표현한다:
--   회원  = uk_report_reporter_target(reporter_member_id, target_type, target_id) — 기존 유지
--   게스트·설치 = uk_report_reporter_installation_target(reporter_installation_id, target_type, target_id) — 신규
-- MySQL UNIQUE 는 NULL 을 서로 다른 값으로 보므로 (M, NULL) 행이 설치 유니크를 점유하지 않는다.
--
-- CHECK 는 "정확히 하나"가 아니라 "최소 하나"다. 회원 신고가 설치 ID 를 함께 저장하므로 XOR 을 걸면
-- 정상 행이 거절되고, 중복 방지에 실제로 필요한 것은 두 식별자가 모두 없는 행을 막는 것뿐이다.
-- IS NOT NULL 표현식이라 NULL 때문에 판정이 UNKNOWN 으로 빠지지 않는다. 운영·개발 RDS 와 테스트
-- 컨테이너 모두 MySQL 8.4 라 CHECK 가 실제로 강제된다(8.0.16+).
ALTER TABLE `report`
    MODIFY COLUMN `reporter_member_id` bigint NULL,
    ADD COLUMN `reporter_installation_id` varchar(64) NULL,
    ADD UNIQUE KEY `uk_report_reporter_installation_target`
        (`reporter_installation_id`, `target_type`, `target_id`),
    ADD CONSTRAINT `ck_report_reporter_at_least_one`
        CHECK (
            `reporter_member_id` IS NOT NULL
            OR `reporter_installation_id` IS NOT NULL
        );
