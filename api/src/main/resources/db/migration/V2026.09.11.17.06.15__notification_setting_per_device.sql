-- KB-544: 알림 설정을 (회원, 기기) 단위로 재정의한다. installation_id 는 notification_device.installation_id 와 같은 값이고,
-- installation_id 가 NULL 인 기존 행은 구 계약(X-API-Version 2.0 이하, 회원 단위) 전용으로 남긴다 — 삭제·이관하지 않는다.
-- news 는 소식(SCAN_SUGGESTION·NEWS) 기기 토글이다. 광고성 수신 동의 원장(notification_consent)은 회원 단위 그대로다.
-- 고유키를 (member_id) → (member_id, installation_id) 로 교체한다. FK fk_notification_setting_member 가 member_id 인덱스를
-- 필요로 하므로 새 고유키(선두 컬럼 member_id)를 먼저 추가한 뒤 구 고유키를 지운다.
ALTER TABLE notification_setting
    ADD COLUMN installation_id VARCHAR(36) NULL AFTER member_id,
    ADD COLUMN news BOOLEAN NOT NULL DEFAULT FALSE AFTER meal_time,
    ADD UNIQUE KEY uk_notification_setting_member_installation (member_id, installation_id);

ALTER TABLE notification_setting
    DROP INDEX uk_notification_setting_member;
