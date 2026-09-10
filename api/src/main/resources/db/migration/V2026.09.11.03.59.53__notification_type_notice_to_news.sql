-- KB-468: 알림 유형 NOTICE(운영 공지) 를 폐기하고 NEWS(소식 — 광고성, 소식 동의자에게만 발송) 로 대체한다(2026-09-11 결정).
-- notification.type 은 VARCHAR(30) 라 스키마 변경은 없고, 기존 행의 값만 옮긴다.
UPDATE notification SET type = 'NEWS' WHERE type = 'NOTICE';
