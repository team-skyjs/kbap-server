SET SESSION lock_wait_timeout = 10;
ALTER TABLE menu_name_estimate
    ADD COLUMN image_ref VARCHAR(500) NULL,
    ADD COLUMN image_generated_at DATETIME(6) NULL,
    ALGORITHM = INSTANT;
SET SESSION lock_wait_timeout = DEFAULT;
