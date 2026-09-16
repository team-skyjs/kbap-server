-- KB-544: 알림 설정을 (회원, 기기) 단위로 재정의한다. installation_id 는 notification_device.installation_id 와 같은 값이다.
-- 회원 단위 계약은 같은 경로(X-API-Version 1.0 부터)에서 기기 단위 계약으로 대체되므로(2026-09-11, dev 전용 서비스라 계약 교체 허용)
-- 기존 회원 단위 행은 읽는 코드가 없어 지운다 — 기기별 설정은 앱이 다음 실행에서 다시 저장한다.
-- news 는 소식(SCAN_SUGGESTION·NEWS) 기기 토글이다. 광고성 수신 동의 원장(notification_consent)은 회원 단위 그대로다.
-- 고유키를 (member_id) → (member_id, installation_id) 로 교체한다. FK fk_notification_setting_member 가 member_id 인덱스를
-- 필요로 하므로 새 고유키(선두 컬럼 member_id)를 먼저 추가한 뒤 구 고유키를 지운다.
ALTER TABLE notification_setting
    ADD COLUMN installation_id VARCHAR(36) NULL AFTER member_id,
    ADD COLUMN news BOOLEAN NOT NULL DEFAULT FALSE AFTER meal_time;

DELETE FROM notification_setting WHERE installation_id IS NULL;

ALTER TABLE notification_setting
    MODIFY COLUMN installation_id VARCHAR(36) NOT NULL,
    ADD UNIQUE KEY uk_notification_setting_member_installation (member_id, installation_id);

ALTER TABLE notification_setting
    DROP INDEX uk_notification_setting_member;
