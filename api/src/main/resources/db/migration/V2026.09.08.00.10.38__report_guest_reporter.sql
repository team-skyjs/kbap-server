-- KB-459: 게스트 신고 허용. reporter_member_id 를 NULL 허용으로 완화하고 게스트 식별용
-- reporter_installation_id 를 추가한다(ADD/MODIFY only). 중복 신고 차단은 신고자 종류별로 분리:
--   회원  = uk_report_reporter_target(reporter_member_id, target_type, target_id) — 기존 유지
--   게스트 = uk_report_reporter_installation_target(reporter_installation_id, target_type, target_id) — 신규
-- MySQL UNIQUE 는 NULL 을 서로 다른 값으로 취급하므로 두 유니크가 상대 신고자 종류의 행(해당 컬럼 NULL)을 간섭하지 않는다.
ALTER TABLE `report`
    MODIFY COLUMN `reporter_member_id` bigint NULL,
    ADD COLUMN `reporter_installation_id` varchar(64) NULL,
    ADD UNIQUE KEY `uk_report_reporter_installation_target` (`reporter_installation_id`, `target_type`, `target_id`);
