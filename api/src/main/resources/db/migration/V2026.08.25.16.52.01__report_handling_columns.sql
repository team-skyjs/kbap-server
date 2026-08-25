-- KB-375: 신고 처리 상태. 기본값 PENDING 이라 구 코드가 신 스키마 위에서 돌아도 안전하다.
ALTER TABLE `report`
    ADD COLUMN `handle_status` varchar(20) NOT NULL DEFAULT 'PENDING' AFTER `detail`,
    ADD COLUMN `handle_result` varchar(30) DEFAULT NULL AFTER `handle_status`,
    ADD COLUMN `handled_by` bigint DEFAULT NULL AFTER `handle_result`,
    ADD COLUMN `handled_at` datetime(6) DEFAULT NULL AFTER `handled_by`,
    ADD COLUMN `handle_note` varchar(500) DEFAULT NULL AFTER `handled_at`,
    ADD KEY `idx_report_handle_status` (`handle_status`, `id`),
    ADD KEY `idx_report_target` (`target_type`, `target_id`);
