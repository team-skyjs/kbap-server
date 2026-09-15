-- KB-459: 게스트 신고 허용. reporter_member_id 를 NULL 허용으로 완화하고 신고 요청이 발생한 앱 설치를
-- 가리키는 reporter_installation_id 를 추가한다(ADD/MODIFY only — 기존 코드가 무시할 수 있는 가산 변경).
--
-- 컬럼 의미: reporter_member_id = 신고 당시 인증된 회원, reporter_installation_id = 신고가 발생한 앱 설치.
-- 회원 신고는 (M, D), 게스트 신고는 (NULL, D) 로 둘 다 저장한다. 회원 행의 설치 ID 는 감사와
-- "회원이 신고한 뒤 로그아웃해도 같은 기기에서 숨김 유지"에 쓴다. 기존 회원 행은 설치 정보를 복원할 수
-- 없어 (M, NULL) 로 보존하므로 컬럼을 NOT NULL 로 승격하지 않는다.
--
-- 설치 기준 제외 필터가 (reporter_installation_id, target_type) 프리픽스로 조회하므로 같은 모양의
-- 인덱스를 함께 둔다. 기존 uk_report_reporter_target 유니크는 여기서 건드리지 않는다 — 이 마이그레이션이
-- 적용된 뒤 기능 PR(#250)이 머지되기 전까지는 구 코드가 그 유니크로 중복 신고를 막고 있기 때문이다.
-- 유니크 제거는 재신고를 허용하는 코드와 같은 PR 에서 별도 마이그레이션으로 수행한다.
--
-- CHECK 는 두 식별자가 모두 없는 행만 막는다(구 코드는 항상 회원 ID 를 쓰므로 무해). IS NOT NULL
-- 표현식이라 NULL 때문에 판정이 UNKNOWN 으로 빠지지 않는다. 운영·개발 RDS 와 테스트 컨테이너 모두
-- MySQL 8.4 라 CHECK 가 실제로 강제된다(8.0.16+).
ALTER TABLE `report`
    MODIFY COLUMN `reporter_member_id` bigint NULL,
    ADD COLUMN `reporter_installation_id` varchar(64) NULL,
    ADD INDEX `idx_report_reporter_installation` (`reporter_installation_id`, `target_type`),
    ADD CONSTRAINT `ck_report_reporter_at_least_one`
        CHECK (
            `reporter_member_id` IS NOT NULL
            OR `reporter_installation_id` IS NOT NULL
        );
