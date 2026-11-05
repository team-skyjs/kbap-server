SET SESSION lock_wait_timeout = 10;
ALTER TABLE menu_name_estimate
    ADD COLUMN promoted_at DATETIME(6) NULL,
    ADD COLUMN promoted_by BIGINT NULL,
    ALGORITHM = INSTANT;
SET SESSION lock_wait_timeout = DEFAULT;
