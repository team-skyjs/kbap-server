-- KB-329: 앱 버전 정보 단일 행 테이블. 공개 조회 API 가 읽고 관리자 API 가 갱신한다.
-- 시드 행을 함께 넣어 "행 없음" 상태로 서비스되는 일이 없게 한다. 스토어 링크는 관리자가 채운다.

CREATE TABLE app_version
(
    id                    BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    min_supported_version VARCHAR(20)               NOT NULL,
    latest_version        VARCHAR(20)               NOT NULL,
    ios_store_url         VARCHAR(512)              NULL,
    aos_store_url         VARCHAR(512)              NULL,
    status                ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at            DATETIME(6)               NOT NULL,
    updated_at            DATETIME(6)               NOT NULL
);

INSERT INTO app_version (min_supported_version, latest_version, ios_store_url, aos_store_url, created_at, updated_at)
VALUES ('1.0.0', '1.0.1', NULL, NULL, NOW(6), NOW(6));
