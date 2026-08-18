ALTER TABLE food_review
    ADD COLUMN serving_speed_rating TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN staff_kindness_rating TINYINT NOT NULL DEFAULT 0;
