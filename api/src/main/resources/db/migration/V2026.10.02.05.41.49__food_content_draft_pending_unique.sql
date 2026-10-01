-- KB-687: "음식당 검수 대기(PENDING) 초안은 하나"를 DB 가 강제한다.
-- 짝 규칙의 예외: 이 파일은 단독으로 적용된다(동봉할 코드 PR 이 없다). 대체를 먼저 flush 하는 코드(#358)가
-- 먼저 배포돼 있어야 한다 — 그 전 코드는 대체 도중 잠깐 검수 대기 초안을 둘로 만들어 이 제약을 어긴다.
--
-- 가산만: 생성 컬럼 하나와 유니크 인덱스 하나. 기존 컬럼·값을 바꾸지 않는다.
-- image_batch_item.pending_food_id 와 같은 방식이다 — 검수 대기이고 살아 있는(ACTIVE) 행만 food_id 값을 갖고
-- 나머지는 NULL 이다. NULL 은 유니크에서 중복이 허용되므로 대체·승인·반려 이력은 여러 개 남는다.
-- 지금까지는 수신 콜백이 음식 행 잠금 아래에서 지켰다. 어드민 음식 상세가 PENDING 초안을 단건으로 읽게 되면서
-- (KB-686) 이 불변식이 깨지면 상세 조회가 통째로 실패하므로 DB 에서도 막는다.
--
-- 가상(VIRTUAL) 생성 컬럼이라 행을 다시 쓰지 않는다(INSTANT). 인덱스는 따로 만든다 — 표를 잠그지 않는다.
ALTER TABLE food_content_draft
    ADD COLUMN pending_food_id BIGINT GENERATED ALWAYS AS (IF(review_status = 'PENDING' AND status = 'ACTIVE', food_id, NULL)) VIRTUAL,
    ALGORITHM = INSTANT;

ALTER TABLE food_content_draft
    ADD UNIQUE INDEX uq_food_content_draft_pending_food (pending_food_id),
    ALGORITHM = INPLACE,
    LOCK = NONE;
