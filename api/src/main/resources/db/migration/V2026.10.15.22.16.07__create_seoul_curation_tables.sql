-- KB-794: migration-only 선행 배포. 게시 콘텐츠 없이 고정 편집 대상만 준비한다.
CREATE TABLE curation_release (
    id BIGINT NOT NULL AUTO_INCREMENT,
    region_key VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    first_activated_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    status ENUM('ACTIVE', 'DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_curation_release_region (region_key),
    CONSTRAINT ck_curation_release_region CHECK (region_key = 'SEOUL'),
    CONSTRAINT ck_curation_release_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE curation_collection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    release_id BIGINT NOT NULL,
    collection_key VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
    draft_document JSON NOT NULL,
    published_document JSON NULL,
    version BIGINT NOT NULL DEFAULT 0,
    published_revision BIGINT NOT NULL DEFAULT 0,
    published_source_version BIGINT NULL,
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at DATETIME(6) NULL,
    published_by BIGINT NULL,
    updated_by BIGINT NULL,
    unpublished_at DATETIME(6) NULL,
    unpublished_by BIGINT NULL,
    status ENUM('ACTIVE', 'DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_curation_collection_release_key (release_id, collection_key),
    CONSTRAINT fk_curation_collection_release FOREIGN KEY (release_id) REFERENCES curation_release (id),
    CONSTRAINT fk_curation_collection_published_by FOREIGN KEY (published_by) REFERENCES admin_account (id),
    CONSTRAINT fk_curation_collection_updated_by FOREIGN KEY (updated_by) REFERENCES admin_account (id),
    CONSTRAINT fk_curation_collection_unpublished_by FOREIGN KEY (unpublished_by) REFERENCES admin_account (id),
    CONSTRAINT ck_curation_collection_key CHECK (collection_key IN ('sights', 'stations', 'restaurants')),
    CONSTRAINT ck_curation_collection_draft CHECK (JSON_TYPE(draft_document) = 'OBJECT'),
    CONSTRAINT ck_curation_collection_published CHECK (
        published_document IS NULL OR JSON_TYPE(published_document) = 'OBJECT'
    ),
    CONSTRAINT ck_curation_collection_versions CHECK (
        version >= 0 AND published_revision >= 0
        AND (published_source_version IS NULL OR published_source_version >= 0)
    ),
    CONSTRAINT ck_curation_collection_publication CHECK (
        is_published IN (0, 1)
        AND (is_published = 0 OR published_document IS NOT NULL)
    ),
    CONSTRAINT ck_curation_collection_snapshot CHECK (
        (published_document IS NULL AND published_revision = 0 AND published_source_version IS NULL
            AND published_at IS NULL AND published_by IS NULL)
        OR (published_document IS NOT NULL AND published_revision > 0 AND published_source_version IS NOT NULL
            AND published_at IS NOT NULL AND published_by IS NOT NULL)
    ),
    CONSTRAINT ck_curation_collection_unpublish_audit CHECK (
        (unpublished_at IS NULL AND unpublished_by IS NULL)
        OR (unpublished_at IS NOT NULL AND unpublished_by IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE curation_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    collection_id BIGINT NOT NULL,
    issued_by_admin_id BIGINT NOT NULL,
    asset_state VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ISSUED',
    staging_key VARCHAR(512) COLLATE utf8mb4_0900_bin NOT NULL,
    final_key VARCHAR(512) COLLATE utf8mb4_0900_bin NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    complete_deadline DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    unreferenced_since DATETIME(6) NULL,
    source_fingerprint VARCHAR(255) COLLATE utf8mb4_0900_bin NULL,
    status ENUM('ACTIVE', 'DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_curation_asset_staging_key (staging_key),
    UNIQUE KEY uk_curation_asset_final_key (final_key),
    KEY idx_curation_asset_expiry (status, asset_state, complete_deadline),
    KEY idx_curation_asset_unreferenced (status, asset_state, unreferenced_since),
    CONSTRAINT fk_curation_asset_collection FOREIGN KEY (collection_id) REFERENCES curation_collection (id),
    CONSTRAINT fk_curation_asset_admin FOREIGN KEY (issued_by_admin_id) REFERENCES admin_account (id),
    CONSTRAINT ck_curation_asset_state CHECK (
        asset_state IN ('ISSUED', 'READY', 'REJECTED', 'EXPIRED', 'DELETING', 'DELETED')
    ),
    CONSTRAINT ck_curation_asset_size CHECK (size_bytes > 0),
    CONSTRAINT ck_curation_asset_deadline CHECK (complete_deadline >= expires_at),
    CONSTRAINT ck_curation_asset_ready CHECK (
        asset_state <> 'READY' OR (final_key IS NOT NULL AND completed_at IS NOT NULL AND source_fingerprint IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 최초 활성화와 10항목/10언어/권리 검증은 후속 게시 서비스의 원자적 트랜잭션이 담당한다.
INSERT INTO curation_release (region_key) VALUES ('SEOUL');

INSERT INTO curation_collection (release_id, collection_key, draft_document)
SELECT r.id, k.collection_key,
       JSON_OBJECT('title', JSON_OBJECT(), 'description', JSON_OBJECT(), 'cover', NULL, 'items', JSON_ARRAY())
FROM curation_release r
CROSS JOIN (
    SELECT 'sights' AS collection_key
    UNION ALL SELECT 'stations'
    UNION ALL SELECT 'restaurants'
) k
WHERE r.region_key = 'SEOUL';
