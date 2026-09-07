-- 푸시 알림 저장 기반(KB-464): notification_device(기기 토큰·게스트 광고성 동의), notification_setting(회원 알림 설정),
-- notification(알림 이력), notification_dispatch(Expo 발송 추적). 참조하는 member 는 init 스키마에 존재한다.

CREATE TABLE notification_device
(
    id                        BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    installation_id           VARCHAR(36)               NOT NULL,
    member_id                 BIGINT                    NULL,
    expo_token                VARCHAR(255)              NOT NULL,
    platform                  ENUM ('IOS','ANDROID')    NOT NULL,
    lang                      VARCHAR(10)               NOT NULL,
    marketing                 BOOLEAN                   NOT NULL DEFAULT FALSE,
    marketing_consent_version VARCHAR(20)               NULL,
    marketing_opt_in_at       DATETIME(6)               NULL,
    status                    ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at                DATETIME(6)               NOT NULL,
    updated_at                DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_notification_device_installation (installation_id),
    KEY idx_notification_device_member (member_id),
    CONSTRAINT fk_notification_device_member FOREIGN KEY (member_id) REFERENCES member (id)
);

CREATE TABLE notification_setting
(
    id                        BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id                 BIGINT                    NOT NULL,
    helpful                   BOOLEAN                   NOT NULL DEFAULT TRUE,
    review_reminder           BOOLEAN                   NOT NULL DEFAULT TRUE,
    marketing                 BOOLEAN                   NOT NULL DEFAULT FALSE,
    marketing_consent_version VARCHAR(20)               NULL,
    marketing_opt_in_at       DATETIME(6)               NULL,
    status                    ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at                DATETIME(6)               NOT NULL,
    updated_at                DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_notification_setting_member (member_id),
    CONSTRAINT fk_notification_setting_member FOREIGN KEY (member_id) REFERENCES member (id)
);

CREATE TABLE notification
(
    id              BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id       BIGINT                    NULL,
    installation_id VARCHAR(36)               NULL,
    type            VARCHAR(30)               NOT NULL,
    title           VARCHAR(200)              NOT NULL,
    body            VARCHAR(1000)             NOT NULL,
    data            JSON                      NULL,
    read_at         DATETIME(6)               NULL,
    status          ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6)               NOT NULL,
    updated_at      DATETIME(6)               NOT NULL,
    KEY idx_notification_member_id (member_id, id),
    KEY idx_notification_installation_id (installation_id, id),
    CONSTRAINT fk_notification_member FOREIGN KEY (member_id) REFERENCES member (id)
);

CREATE TABLE notification_dispatch
(
    id                     BIGINT                                       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    notification_id        BIGINT                                       NOT NULL,
    notification_device_id BIGINT                                       NULL,
    expo_token             VARCHAR(255)                                 NOT NULL,
    ticket_id              VARCHAR(64)                                  NULL,
    dispatch_status        ENUM ('PENDING','SENT','DELIVERED','FAILED') NOT NULL DEFAULT 'PENDING',
    error                  VARCHAR(255)                                 NULL,
    status                 ENUM ('ACTIVE','DELETED')                    NOT NULL DEFAULT 'ACTIVE',
    created_at             DATETIME(6)                                  NOT NULL,
    updated_at             DATETIME(6)                                  NOT NULL,
    KEY idx_notification_dispatch_notification (notification_id),
    KEY idx_notification_dispatch_status_created (dispatch_status, created_at),
    CONSTRAINT fk_notification_dispatch_notification FOREIGN KEY (notification_id) REFERENCES notification (id)
);
