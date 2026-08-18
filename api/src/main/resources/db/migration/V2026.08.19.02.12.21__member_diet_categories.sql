ALTER TABLE member
    ADD COLUMN diet_categories json NOT NULL DEFAULT (JSON_ARRAY());
