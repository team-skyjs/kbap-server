-- AI 검수 파이프라인 연동 — content_status 에 REVIEWED·REVIEW_REJECTED 추가 + 검수 시도/탈락 사유 컬럼(KB-296).
ALTER TABLE food
    MODIFY COLUMN content_status
        ENUM('INCOMPLETE', 'PENDING_IMAGE', 'PENDING_REVIEW', 'REVIEWED', 'REVIEW_REJECTED', 'READY')
        NOT NULL DEFAULT 'READY';

ALTER TABLE food
    ADD COLUMN content_review_attempts INT NOT NULL DEFAULT 0 AFTER content_status,
    ADD COLUMN content_review_rejection_reason VARCHAR(1000) NULL AFTER content_review_attempts;
