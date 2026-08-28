-- KB-375: 콘텐츠 수집 요청의 취소 상태와 마지막 발행 실패 사유. ENUM 값 추가·NULL 컬럼만이라 리비전 공존 안전.
ALTER TABLE `food_content_outbox`
    MODIFY COLUMN `outbox_status` enum('PENDING','SENT','COMPLETE','CANCELED') NOT NULL,
    ADD COLUMN `last_error` varchar(500) DEFAULT NULL AFTER `sent_at`,
    ADD COLUMN `last_failed_at` datetime(6) DEFAULT NULL AFTER `last_error`,
    ADD INDEX `idx_food_content_outbox_status_sent` (`outbox_status`, `sent_at`);
