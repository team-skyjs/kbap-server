-- 회원 차단(KB-131): 단방향 차단 관계. 쌍당 1행 — 해제는 소프트삭제(status=DELETED), 재차단은 부활.
-- 조회용 별도 인덱스 없음: 쌍 단건·blocker 목록 조회 모두 uk 좌측 접두(blocker_member_id)로 커버.
CREATE TABLE `member_block` (
    `id`                bigint      NOT NULL AUTO_INCREMENT,
    `blocker_member_id` bigint      NOT NULL,
    `blocked_member_id` bigint      NOT NULL,
    `status`            enum('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`        datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`        datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_block_pair` (`blocker_member_id`, `blocked_member_id`),
    CONSTRAINT `fk_member_block_blocker` FOREIGN KEY (`blocker_member_id`) REFERENCES `member` (`id`),
    CONSTRAINT `fk_member_block_blocked` FOREIGN KEY (`blocked_member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
