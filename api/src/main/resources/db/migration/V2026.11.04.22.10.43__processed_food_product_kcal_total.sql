-- S-957(예진 11/4): 가공식품 칼로리 표시. 포장 전체 기준 kcal(사람이 넣은 값만 — 수동 등록·정정, 어드민 kcal 입력). HACCP nutrients 자동 추출은 하지 않는다.
-- NULL = 모름. 앱은 용량이 다른 상품이 한 묶음이면 kcal 을 보이지 않는다(규칙은 코드 소유).
-- 가산만: NULL 컬럼 추가. 위치 지정(AFTER) 없이 INSTANT 로 끝나게 하고, 못 하면 조용히 COPY 로 가지 않고 실패하게 ALGORITHM 을 명시한다. 기존 값은 바꾸지 않는다. 구 코드는 이 컬럼을 모른다.
-- lock_wait_timeout 10초: INSTANT 도 배타 메타데이터 잠금을 잡는다. 긴 트랜잭션이 잠금을 쥐고 있으면 기다리는 사이 뒤의 읽기·쓰기까지 줄 선다 — 10초 안에 못 잡으면 실패시키고 재시도한다.
SET SESSION lock_wait_timeout = 10;

ALTER TABLE processed_food_product
    ADD COLUMN kcal_total INT NULL,
    ALGORITHM = INSTANT;

SET SESSION lock_wait_timeout = DEFAULT;
