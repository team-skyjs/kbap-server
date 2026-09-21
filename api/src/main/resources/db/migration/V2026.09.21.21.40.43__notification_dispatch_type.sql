-- 발송 이력에 알림 유형을 기록한다(KB-614). 영수증 확인 잡 두 개(광고성·활동)가 이 컬럼으로 자기 몫을 고른다.
-- NULL 허용: 블루/그린 배포 중 구 버전 코드가 이 컬럼 없이 INSERT 한다. NOT NULL 전환은 다음 릴리스에서 한다.
ALTER TABLE notification_dispatch
    ADD COLUMN notification_type VARCHAR(30) NULL AFTER notification_device_id;

UPDATE notification_dispatch d
    JOIN notification n ON n.id = d.notification_id
SET d.notification_type = n.type
WHERE d.notification_type IS NULL;
