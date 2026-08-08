-- KB-301: 콘텐츠 생성이 kbap-langchain 으로 이관되며 food 상태를 4값으로 간소화한다.
-- MySQL 은 ENUM 정의에 없는 값이 행에 남아 있으면 축소 MODIFY 가 실패하므로 확장 → 매핑 → 축소 순서가 필수다.
-- 1단계 ENUM 의 구 값(INCOMPLETE·REVIEWED·REVIEW_REJECTED)은 기존 행을 옮기기 위한 일시적 정의이고 3단계에서 사라진다.
-- DEFAULT 는 두지 않는다 — 상태는 파이프라인이 결정하는 값이라 암묵 기본값(구 DEFAULT 'READY')이 있으면
-- 상태를 빠뜨린 INSERT 가 콘텐츠 없는 음식을 사용자에게 즉시 노출시킨다.

ALTER TABLE food
    MODIFY COLUMN content_status
        ENUM('INCOMPLETE','PENDING_IMAGE','PENDING_REVIEW','REVIEWED','REVIEW_REJECTED','READY','FAILED')
        NOT NULL;

UPDATE food SET content_status = 'PENDING_REVIEW' WHERE content_status = 'REVIEWED';

UPDATE food SET content_status = 'FAILED' WHERE content_status IN ('REVIEW_REJECTED', 'INCOMPLETE');

ALTER TABLE food
    MODIFY COLUMN content_status
        ENUM('FAILED','PENDING_IMAGE','PENDING_REVIEW','READY')
        NOT NULL;
