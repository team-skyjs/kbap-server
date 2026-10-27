-- KB-744 W1: schema preparation only. No common mappings or existing data changes.
-- Apply in dev/prod before deploying ledger mappings; runtime remains LEGACY/new modes OFF.
-- Cross-row owner/unit/revision consistency and result+usage atomicity belong to W2 transactions.
-- Q5 aggregate/revision/translation storage is a separate additive migration.

CREATE TABLE scan_runtime_control (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    singleton_key TINYINT NOT NULL DEFAULT 1,
    routing_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
    new_modes_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    global_call_limit INT NOT NULL DEFAULT 4,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_runtime_singleton (singleton_key),
    CONSTRAINT ck_scan_runtime_singleton CHECK (singleton_key = 1),
    CONSTRAINT ck_scan_runtime_call_limit CHECK (global_call_limit > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_operation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    member_id BIGINT NOT NULL,
    key_namespace VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key BINARY(16) NOT NULL,
    manifest_hash BINARY(32) NOT NULL,
    mode VARCHAR(24) NOT NULL,
    lifecycle VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    initial_language VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    fx_state VARCHAR(24) NOT NULL,
    fx_krw_per_unit DECIMAL(19,4) NULL,
    fx_provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    fx_rate_date DATE NULL,
    fx_observed_at DATETIME(6) NULL,
    request_schema_version SMALLINT NOT NULL DEFAULT 1,
    request_document JSON NOT NULL,
    current_manifest_revision INT NOT NULL DEFAULT 1,
    limits_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    reservation_until DATETIME(6) NULL,
    reservation_epoch BIGINT NOT NULL DEFAULT 0,
    usage_recorded_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    result_expires_at DATETIME(6) NOT NULL,
    key_retire_after DATETIME(6) NOT NULL,
    result_revision BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_operation_request (member_id, key_namespace, request_key),
    KEY idx_scan_operation_reservation (member_id, reservation_state, reservation_until),
    KEY idx_scan_operation_expiry (lifecycle, expires_at),
    KEY idx_scan_operation_result_expiry (result_expires_at, id),
    KEY idx_scan_operation_key_retirement (key_retire_after, id),
    CONSTRAINT fk_scan_operation_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT ck_scan_operation_fx CHECK (fx_krw_per_unit IS NULL OR fx_krw_per_unit > 0),
    CONSTRAINT ck_scan_operation_revision CHECK (current_manifest_revision > 0 AND result_revision >= 0),
    CONSTRAINT ck_scan_operation_epoch CHECK (reservation_epoch >= 0 AND version >= 0),
    CONSTRAINT ck_scan_operation_expiry CHECK (expires_at <= result_expires_at AND result_expires_at <= key_retire_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_operation_photo (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    operation_id BIGINT NOT NULL,
    photo_id BINARY(16) NOT NULL,
    selection_index INT NOT NULL,
    uploaded_image_id BIGINT NOT NULL,
    object_key VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
    source_fingerprint JSON NOT NULL,
    byte_size BIGINT NOT NULL,
    pixel_width INT NULL,
    pixel_height INT NULL,
    execution_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_no INT NOT NULL DEFAULT 0,
    lease_token BINARY(16) NULL,
    lease_until DATETIME(6) NULL,
    error_reason VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_photo_identity (operation_id, photo_id),
    UNIQUE KEY uk_scan_photo_selection (operation_id, selection_index),
    UNIQUE KEY uk_scan_photo_object (operation_id, object_key),
    KEY idx_scan_photo_lease (execution_state, lease_until),
    CONSTRAINT fk_scan_photo_operation FOREIGN KEY (operation_id) REFERENCES scan_operation (id),
    CONSTRAINT fk_scan_photo_upload FOREIGN KEY (uploaded_image_id) REFERENCES uploaded_image (id),
    CONSTRAINT ck_scan_photo_position CHECK (selection_index >= 0 AND attempt_no >= 0),
    CONSTRAINT ck_scan_photo_size CHECK (byte_size > 0),
    CONSTRAINT ck_scan_photo_pixels CHECK ((pixel_width IS NULL AND pixel_height IS NULL) OR (pixel_width IS NOT NULL AND pixel_height IS NOT NULL AND pixel_width > 0 AND pixel_height > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    operation_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    photo_row_id BIGINT NULL,
    unit_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    phase VARCHAR(24) NOT NULL,
    target_manifest_revision INT NOT NULL,
    attempt_no INT NOT NULL,
    claim_token BINARY(16) NOT NULL,
    reservation_epoch BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    provider_request_ref VARCHAR(255) NULL,
    started_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    call_slot_held BOOLEAN NOT NULL DEFAULT FALSE,
    call_finished_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    failure_kind VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_attempt_unit (operation_id, unit_key, attempt_no),
    KEY idx_scan_attempt_global_slot (call_slot_held, lease_until),
    KEY idx_scan_attempt_member_slot (member_id, call_slot_held, lease_until),
    KEY idx_scan_attempt_operation_slot (operation_id, call_slot_held, lease_until),
    CONSTRAINT fk_scan_attempt_operation FOREIGN KEY (operation_id) REFERENCES scan_operation (id),
    CONSTRAINT fk_scan_attempt_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_scan_attempt_photo FOREIGN KEY (photo_row_id) REFERENCES scan_operation_photo (id),
    CONSTRAINT ck_scan_attempt_identity CHECK (attempt_no > 0 AND target_manifest_revision > 0 AND reservation_epoch >= 0),
    CONSTRAINT ck_scan_attempt_deadline CHECK (started_at < deadline_at AND deadline_at <= lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_operation_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    operation_id BIGINT NOT NULL,
    photo_row_id BIGINT NOT NULL,
    committed_attempt_id BIGINT NOT NULL,
    committed_attempt_no INT NOT NULL,
    target_manifest_revision INT NOT NULL,
    result_document JSON NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_result_photo (photo_row_id),
    KEY idx_scan_result_operation (operation_id),
    CONSTRAINT fk_scan_result_operation FOREIGN KEY (operation_id) REFERENCES scan_operation (id),
    CONSTRAINT fk_scan_result_photo FOREIGN KEY (photo_row_id) REFERENCES scan_operation_photo (id),
    CONSTRAINT fk_scan_result_attempt FOREIGN KEY (committed_attempt_id) REFERENCES scan_attempt (id),
    CONSTRAINT ck_scan_result_attempt CHECK (committed_attempt_no > 0 AND target_manifest_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    operation_id BIGINT NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    entitlement_at_commit VARCHAR(24) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_usage_operation (operation_id),
    CONSTRAINT fk_scan_usage_operation FOREIGN KEY (operation_id) REFERENCES scan_operation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_merged_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    operation_id BIGINT NOT NULL,
    public_id BINARY(16) NOT NULL,
    merge_key BINARY(32) NULL,
    document JSON NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_merged_public (operation_id, public_id),
    UNIQUE KEY uk_scan_merged_key (operation_id, merge_key),
    CONSTRAINT fk_scan_merged_operation FOREIGN KEY (operation_id) REFERENCES scan_operation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_occurrence (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    operation_id BIGINT NOT NULL,
    photo_row_id BIGINT NOT NULL,
    occurrence_key BINARY(16) NOT NULL,
    row_index INT NOT NULL,
    raw_document JSON NOT NULL,
    comparison_document JSON NOT NULL,
    matched_food_id BIGINT NULL,
    merged_item_id BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_occurrence_identity (photo_row_id, occurrence_key),
    KEY idx_scan_occurrence_operation (operation_id),
    CONSTRAINT fk_scan_occurrence_operation FOREIGN KEY (operation_id) REFERENCES scan_operation (id),
    CONSTRAINT fk_scan_occurrence_photo FOREIGN KEY (photo_row_id) REFERENCES scan_operation_photo (id),
    CONSTRAINT fk_scan_occurrence_food FOREIGN KEY (matched_food_id) REFERENCES food (id),
    CONSTRAINT fk_scan_occurrence_merged FOREIGN KEY (merged_item_id) REFERENCES scan_merged_item (id),
    CONSTRAINT ck_scan_occurrence_row CHECK (row_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_history_source (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    history_id BIGINT NOT NULL,
    occurrence_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_history_source_history (history_id),
    UNIQUE KEY uk_scan_history_source_occurrence (occurrence_id),
    CONSTRAINT fk_scan_history_source_history FOREIGN KEY (history_id) REFERENCES scan_history (id),
    CONSTRAINT fk_scan_history_source_occurrence FOREIGN KEY (occurrence_id) REFERENCES scan_occurrence (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scan_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    operation_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    request_key BINARY(16) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    result_revision BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_order_operation (operation_id),
    UNIQUE KEY uk_scan_order_order (order_id),
    CONSTRAINT fk_scan_order_operation FOREIGN KEY (operation_id) REFERENCES scan_operation (id),
    CONSTRAINT fk_scan_order_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_scan_order_revision CHECK (result_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_photo (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    order_id BIGINT NOT NULL,
    operation_photo_id BIGINT NOT NULL,
    selection_index INT NOT NULL,
    object_key VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_photo_selection (order_id, selection_index),
    UNIQUE KEY uk_order_photo_source (order_id, operation_photo_id),
    CONSTRAINT fk_order_photo_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_photo_source FOREIGN KEY (operation_photo_id) REFERENCES scan_operation_photo (id),
    CONSTRAINT ck_order_photo_selection CHECK (selection_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_item_source (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    order_item_id BIGINT NOT NULL,
    occurrence_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item_source (order_item_id, occurrence_id),
    CONSTRAINT fk_order_item_source_item FOREIGN KEY (order_item_id) REFERENCES order_item (id),
    CONSTRAINT fk_order_item_source_occurrence FOREIGN KEY (occurrence_id) REFERENCES scan_occurrence (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO scan_runtime_control (id, routing_mode, new_modes_enabled, global_call_limit)
VALUES (1, 'LEGACY', FALSE, 4);
