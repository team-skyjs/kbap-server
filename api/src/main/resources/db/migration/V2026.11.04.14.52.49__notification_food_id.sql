ALTER TABLE notification
    ADD COLUMN food_id BIGINT NULL AFTER type,
    ADD INDEX idx_notification_member_type_created (member_id, type, created_at);
