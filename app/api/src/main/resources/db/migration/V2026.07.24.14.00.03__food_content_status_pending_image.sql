-- content_status 에 PENDING_IMAGE(텍스트 완료·이미지 대기) 추가(KB-226).
-- 텍스트 4작업 완료 + 이미지 부재 음식이 머무는 대기실 — 콘텐츠 배치 선정(INCOMPLETE)에서 빠진다.
-- 기존 행은 값 변경 없음. DEFAULT 'READY' 유지 — 애플리케이션은 항상 명시값 사용.
ALTER TABLE food
    MODIFY COLUMN content_status ENUM('INCOMPLETE', 'PENDING_IMAGE', 'PENDING_REVIEW', 'READY') NOT NULL DEFAULT 'READY';
