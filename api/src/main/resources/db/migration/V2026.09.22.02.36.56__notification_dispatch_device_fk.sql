-- notification_dispatch.notification_device_id 는 생성 시점에 외래키가 빠져 있었다(V2026.09.07.15.39.45).
-- 같은 테이블의 notification_id 와 달리 제약이 없어 존재하지 않는 기기 id 가 들어가도 DB 가 막지 못했다.
-- 컬럼은 NULL 을 허용한다(기기를 특정하지 못한 발송 기록). ON DELETE 는 두지 않는다 — 소프트 삭제 구조다.
ALTER TABLE notification_dispatch
    ADD CONSTRAINT fk_notification_dispatch_device
        FOREIGN KEY (notification_device_id) REFERENCES notification_device (id);
