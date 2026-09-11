-- KB-459: 게스트 신고 허용. reporter_member_id 를 NULL 허용으로 완화하고 게스트 식별용
-- reporter_installation_id 를 추가한다(ADD/MODIFY only). 중복 신고 차단은 신고자 종류별로 분리:
--   회원  = uk_report_reporter_target(reporter_member_id, target_type, target_id) — 기존 유지
--   게스트 = uk_report_reporter_installation_target(reporter_installation_id, target_type, target_id) — 신규
-- MySQL UNIQUE 는 NULL 을 서로 다른 값으로 취급하므로 두 유니크가 상대 신고자 종류의 행(해당 컬럼 NULL)을 간섭하지 않는다.
ALTER TABLE `report`
    MODIFY COLUMN `reporter_member_id` bigint NULL,
    ADD COLUMN `reporter_installation_id` varchar(64) NULL,
    ADD UNIQUE KEY `uk_report_reporter_installation_target` (`reporter_installation_id`, `target_type`, `target_id`);

-- 신고자 식별자는 정확히 하나만 채운다(XOR). 근거: ReportService 는 회원 신고에 Report.byMember
-- (reporter_installation_id = NULL), 게스트 신고에 Report.byGuest(reporter_member_id = NULL) 만
-- 쓰고 두 값을 함께 저장하는 경로가 없다 — 그래서 "최소 하나"가 아니라 "정확히 하나"로 조인다.
-- 이 제약이 없으면 두 컬럼이 모두 NULL 인 행을 어느 유니크도 막지 못한다(MySQL 은 NULL 이 든 키를
-- 서로 다르게 본다). 회원 컬럼이 NOT NULL 이던 시절 보장되던 중복 신고 차단이 그대로 사라진다.
-- 운영·개발 RDS 와 테스트 컨테이너 모두 MySQL 8.4 라 CHECK 가 파싱만이 아니라 실제로 강제된다(8.0.16+).
ALTER TABLE `report`
    ADD CONSTRAINT `ck_report_reporter_exactly_one`
        CHECK ((`reporter_member_id` IS NULL) <> (`reporter_installation_id` IS NULL));
