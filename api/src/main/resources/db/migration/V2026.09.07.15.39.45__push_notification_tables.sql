-- 푸시 알림 저장 기반(KB-464): notification_device(기기 토큰 — 삭제하지 않고 token_invalid_at 로 무효화),
-- notification_setting(회원 선호), notification_consent(광고성 수신 동의 원장 — 행 1개 = 동의 1회의 생애, append-only),
-- notification(알림 이력), notification_dispatch(Expo 발송 추적). 참조하는 member 는 init 스키마에 존재한다.

CREATE TABLE notification_device
(
    id                        BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    installation_id           VARCHAR(36)               NOT NULL,
    member_id                 BIGINT                    NULL,
    expo_token                VARCHAR(255)              NOT NULL,
    platform                  ENUM ('IOS','ANDROID')    NOT NULL,
    lang                      VARCHAR(10)               NOT NULL,
    token_invalid_at          DATETIME(6)               NULL,
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
    status                    ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at                DATETIME(6)               NOT NULL,
    updated_at                DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_notification_setting_member (member_id),
    CONSTRAINT fk_notification_setting_member FOREIGN KEY (member_id) REFERENCES member (id)
);

CREATE TABLE notification_consent
(
    id              BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id       BIGINT                    NULL,
    installation_id VARCHAR(36)               NULL,
    consent_version SMALLINT UNSIGNED         NOT NULL,
    granted_at      DATETIME(6)               NOT NULL,
    revoked_at      DATETIME(6)               NULL,
    status          ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6)               NOT NULL,
    updated_at      DATETIME(6)               NOT NULL,
    KEY idx_notification_consent_member (member_id, revoked_at),
    KEY idx_notification_consent_installation (installation_id, revoked_at),
    KEY idx_notification_consent_open (revoked_at, consent_version, granted_at),
    CONSTRAINT fk_notification_consent_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT ck_notification_consent_subject CHECK (member_id IS NOT NULL OR installation_id IS NOT NULL)
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
