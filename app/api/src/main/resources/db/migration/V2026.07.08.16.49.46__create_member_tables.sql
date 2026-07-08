CREATE TABLE members
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    nickname                  VARCHAR(30)  NULL,
    avoidance_substance_codes JSON         NOT NULL,
    spiciness_preference      INT          NULL,
    country_code              VARCHAR(2)   NULL,
    app_language              VARCHAR(10)  NULL,
    onboarding_status         VARCHAR(20)  NOT NULL,
    status                    VARCHAR(20)  NOT NULL,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE member_social_identities
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    member_id        BIGINT       NOT NULL,
    provider         VARCHAR(20)  NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    email            VARCHAR(255) NULL,
    status           VARCHAR(20)  NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_social_identity_provider_user (provider, provider_user_id),
    KEY idx_social_identity_member (member_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
