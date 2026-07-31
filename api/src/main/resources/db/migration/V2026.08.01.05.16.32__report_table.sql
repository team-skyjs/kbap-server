-- 신고(KB-129): 대상 타입 + 대상 id 의 일반 신고 모델 — 이번 범위 대상은 리뷰(REVIEW)뿐.
-- target_id 에 FK 를 걸지 않는다: 대상이 다형(리뷰, 이후 커뮤니티 게시글)이고,
-- 대상 삭제 후에도 신고 기록은 보존한다. 대상 존재 검증은 유스케이스가 수행한다.
-- UNIQUE (reporter_member_id, target_type, target_id) 가 중복 신고를 DB 레벨에서 차단한다
-- (신고 취소가 없어 소프트 삭제 행과 충돌하지 않음). 신고자 기준 제외 필터 조회는
-- 이 UNIQUE 인덱스의 (reporter_member_id, target_type) 프리픽스를 탄다.
CREATE TABLE `report` (
    `id`                 bigint       NOT NULL AUTO_INCREMENT,
    `reporter_member_id` bigint       NOT NULL,
    `target_type`        varchar(20)  NOT NULL,
    `target_id`          bigint       NOT NULL,
    `reason`             varchar(20)  NOT NULL,
    `detail`             varchar(500)     NULL,
    `status`             enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`         datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`         datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_reporter_target` (`reporter_member_id`, `target_type`, `target_id`),
    CONSTRAINT `fk_report_member` FOREIGN KEY (`reporter_member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
