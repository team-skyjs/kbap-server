-- KB-375: 관리자 제재 기록. NULL 허용 — 구 코드가 신 스키마 위에서 돌아도 안전하다.
ALTER TABLE `member`
    ADD COLUMN `suspended_at` datetime(6) DEFAULT NULL AFTER `member_status`,
    ADD COLUMN `suspend_reason` varchar(500) DEFAULT NULL AFTER `suspended_at`;
