-- S-964(KB-996, 예진 11/5): 콘텐츠 검수 반려 사유를 자동 재생성에 쓴다. 반려(1·2회)하면 그 사유를 실어 재수집 요청을 쌓고,
-- 퍼블리셔가 랭체인 Lambda 호출 payload 의 rejectionReason 으로 넘긴다(랭체인이 프롬프트에 반영).
-- rejection_reason: 그 요청을 만든 반려 사유(food.content_review_rejection_reason 과 같은 1,000자 상한). 수동 재수집·신규 요청은 NULL.
-- 가산만: NULL 컬럼 추가. 위치 지정(AFTER) 없이 INSTANT 로 끝나게 하고, 못 하면 조용히 COPY 로 가지 않고 실패하게 ALGORITHM 을 명시한다.
-- 기존 값은 바꾸지 않는다. 구 코드는 이 컬럼을 모른다.
-- lock_wait_timeout 10초: INSTANT 도 배타 메타데이터 잠금을 잡는다 — 퍼블리셔·회수 트랜잭션이 잠금을 쥐고 있으면 10초 뒤 실패시키고 재시도한다.
SET SESSION lock_wait_timeout = 10;

ALTER TABLE food_content_outbox
    ADD COLUMN rejection_reason VARCHAR(1000) NULL,
    ALGORITHM = INSTANT;

SET SESSION lock_wait_timeout = DEFAULT;
