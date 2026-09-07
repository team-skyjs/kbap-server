-- KB-466: 알림 설정을 두 그룹으로 재편한다 — 활동 푸시(활동/소식 토글 1개)·K-Bap에서 보내는 소식(두 동의 + 식사 시간 알림 토글 1개).
-- notification_setting 의 helpful·review_reminder 는 activity 하나로 통합, meal_time 은 점심·저녁 넛지 토글.
-- notification_consent 는 동의 종류(consent_type) 축이 생긴다 — MARKETING_PRIVACY(마케팅 목적 개인정보 수집·이용) / MARKETING_RECEIVE(광고성 정보 수신).
-- 기존 행은 광고성 수신 동의뿐이므로 MARKETING_RECEIVE 로 채운 뒤 DEFAULT 를 제거한다.

ALTER TABLE notification_setting
    DROP COLUMN helpful,
    DROP COLUMN review_reminder,
    ADD COLUMN activity  BOOLEAN NOT NULL DEFAULT TRUE AFTER member_id,
    ADD COLUMN meal_time BOOLEAN NOT NULL DEFAULT TRUE AFTER activity;

ALTER TABLE notification_consent
    ADD COLUMN consent_type VARCHAR(30) NOT NULL DEFAULT 'MARKETING_RECEIVE' AFTER installation_id;

ALTER TABLE notification_consent
    ALTER COLUMN consent_type DROP DEFAULT;
