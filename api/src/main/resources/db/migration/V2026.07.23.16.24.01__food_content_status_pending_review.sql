-- content_status 에 PENDING_REVIEW(검수 대기) 추가 — 배치 4작업 완비 시 READY 대신 이 상태로 저장(KB-223).
-- 기존 행은 값 변경 없음(READY/INCOMPLETE 유지). DEFAULT 'READY' 유지 — 애플리케이션은 항상 명시값 사용.
ALTER TABLE food
    MODIFY COLUMN content_status ENUM('INCOMPLETE', 'PENDING_REVIEW', 'READY') NOT NULL DEFAULT 'READY';
