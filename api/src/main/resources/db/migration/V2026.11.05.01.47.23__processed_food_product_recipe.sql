-- S-961(KB-994, 예진 11/4): 라면(컵·봉지) 상세의 조리법. 디자인 D-34 B안 데이터 형태가 정본.
-- recipe JSON NULL — NULL 이면 앱이 조리법 섹션을 숨긴다. 숫자(물 ml·선, 와트별 시간, 기다림, 남길 물)는 구조화하고,
-- 단계·주의 문구는 한국어 원문만 저장한다(번역은 content_translation, 새 target_type — VARCHAR 라 스키마 변경 없음).
-- 상품 테이블의 JSON 컬럼 하나로 둔다: 상품과 1:1 이고 상세가 이미 이 행을 읽으므로 조인·새 테이블보다 단순하다.
-- HACCP 미러 갱신(refresh)은 이 컬럼을 건드리지 않는다(사람이 넣은 값만 — 어드민 PUT).
-- 가산만: NULL 컬럼 추가. 위치 지정(AFTER) 없이 INSTANT 로 끝나게 하고, 못 하면 조용히 COPY 로 가지 않고 실패하게 ALGORITHM 을 명시한다. 기존 값은 바꾸지 않는다. 구 코드는 이 컬럼을 모른다.
-- lock_wait_timeout 10초: INSTANT 도 배타 메타데이터 잠금을 잡는다 — 10초 안에 못 잡으면 실패시키고 재시도한다.
SET SESSION lock_wait_timeout = 10;

ALTER TABLE processed_food_product
    ADD COLUMN recipe JSON NULL,
    ALGORITHM = INSTANT;

SET SESSION lock_wait_timeout = DEFAULT;
