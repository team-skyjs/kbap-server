-- KB-301: 콘텐츠 생성이 kbap-langchain 으로 이관되며 food 상태를 4값으로 간소화한다.
-- MySQL 은 ENUM 정의에 없는 값이 행에 남아 있으면 축소 MODIFY 가 실패하므로 확장 → 매핑 → 축소 순서가 필수다.

ALTER TABLE food
    MODIFY COLUMN content_status
        ENUM('INCOMPLETE','PENDING_IMAGE','PENDING_REVIEW','REVIEWED','REVIEW_REJECTED','READY','FAILED')
        NOT NULL DEFAULT 'READY';

UPDATE food SET content_status = 'PENDING_REVIEW' WHERE content_status = 'REVIEWED';

UPDATE food SET content_status = 'FAILED' WHERE content_status IN ('REVIEW_REJECTED', 'INCOMPLETE');

ALTER TABLE food
    MODIFY COLUMN content_status
        ENUM('FAILED','PENDING_IMAGE','PENDING_REVIEW','READY')
        NOT NULL DEFAULT 'READY';
