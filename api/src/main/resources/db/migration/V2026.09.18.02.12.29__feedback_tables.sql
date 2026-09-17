-- KB-585: 사용자 문의(피드백). 게스트도 보낼 수 있어야 하므로 member_id 는 NULL 허용이고
-- 기기 식별자 installation_id 가 필수다 — "내 문의" 조회는 installation_id OR member_id 로 매칭해,
-- 게스트로 보낸 문의가 가입 후에도 같은 기기에서 보인다(신고·알림함과 같은 규칙).
--
-- image_refs·device_info 는 JSON 이다. device_info 는 클라이언트가 자동 수집한 9키를 그대로 담고
-- 키 누락을 허용하므로 컬럼으로 펴지 않는다 — 키가 늘어도 스키마를 건드리지 않는다.
-- user_agent·created_at 은 서버가 채운다.
--
-- 컬럼명 주의: BaseEntity 가 전 엔티티에 소프트 삭제용 status(ACTIVE/DELETED)를 쓰므로(@SQLRestriction),
-- 문의의 처리 상태는 member 의 member_status 선례대로 feedback_status 로 분리한다. API 응답 필드명은
-- 계약대로 status 이며 컬럼명만 다르다.
--
-- 인덱스: 내 문의 조회가 installation_id·member_id 로, 어드민 목록이 (feedback_status, created_at) 으로 조회한다.
CREATE TABLE `feedback`
(
    `id`              bigint       NOT NULL AUTO_INCREMENT,
    `member_id`       bigint       NULL,
    `installation_id` varchar(36)  NOT NULL,
    `content`         text         NOT NULL,
    `image_refs`      json         NULL,
    `device_info`     json         NULL,
    `user_agent`      varchar(255) NULL,
    `feedback_status` enum ('OPEN','ANSWERED','CLOSED') NOT NULL DEFAULT 'OPEN',
    `status`          enum ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`      datetime(6)  NOT NULL,
    `updated_at`      datetime(6)  NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_feedback_installation` (`installation_id`),
    KEY `idx_feedback_member` (`member_id`),
    KEY `idx_feedback_status_created` (`feedback_status`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 답변은 어드민만 단다. admin_account_id 는 답변자 추적용이며 앱 응답에는 내보내지 않는다.
CREATE TABLE `feedback_reply`
(
    `id`               bigint      NOT NULL AUTO_INCREMENT,
    `feedback_id`      bigint      NOT NULL,
    `admin_account_id` bigint      NOT NULL,
    `content`          text        NOT NULL,
    `status`           enum ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`       datetime(6) NOT NULL,
    `updated_at`       datetime(6) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_feedback_reply_feedback` (`feedback_id`),
    CONSTRAINT `fk_feedback_reply_feedback` FOREIGN KEY (`feedback_id`) REFERENCES `feedback` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
