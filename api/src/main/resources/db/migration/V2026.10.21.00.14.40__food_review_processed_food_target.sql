-- KB-810 2단계(R-101, 예진 승인 2026-10-21): 리뷰 대상을 음식 | 가공식품으로 넓히는 선행 스키마(exclusive arc).
-- 엔티티·코드 무변 — 구 코드는 target_type 을 모르고 food_id 를 채워 INSERT 하므로 기본값 FOOD 로 그대로 동작한다.
-- food_id NOT NULL → NULL 은 가산 원칙의 예외(예진 승인 10/21). 기존 행 UPDATE 없음.
-- 한 ALTER 문으로 묶어 원자 적용(MySQL 8 atomic DDL) — 중간 실패 시 부분 적용 상태가 남지 않는다.
-- CHECK(COPY 만 지원, 1845)·FK(foreign_key_checks=ON 이면 INPLACE 거부, 1846) 때문에 COPY 재구축 — 재구축 동안 쓰기 대기. 적용 시간은 PR 본문 실측 참고.
-- lock_wait_timeout 10초: 긴 트랜잭션이 메타데이터 잠금을 쥐고 있으면 ALTER 가 오래 기다리는 사이 뒤의 읽기까지 줄 선다 — 10초 안에 못 잡으면 실패시키고 재시도한다.
-- 롤백 범위: 가공식품 리뷰 행이 한 건이라도 생기면 이 스키마 이전 코드(Review.foodId non-null)는 그 행을 읽다 500 이 난다.
--   그래서 짝 코드 PR 은 2단계 — ①-a 기존 리뷰 읽기에 target_type = 'FOOD' 필터만 먼저 배포, ①-b 쓰기 개방(플래그 ON).
--   롤백은 "①-a 이상" 코드로만 한다. 스키마(컬럼·제약)는 지우지 않는다. 구 INSERT 는 기본값 FOOD 로 CHECK 를 통과하므로 이 SQL 단독 선머지는 안전.

SET SESSION lock_wait_timeout = 10;

ALTER TABLE food_review
    ADD COLUMN target_type ENUM('FOOD', 'PROCESSED_FOOD') NOT NULL DEFAULT 'FOOD' AFTER member_id,
    ADD COLUMN processed_food_id BIGINT NULL AFTER food_id,
    MODIFY COLUMN food_id BIGINT NULL,
    ADD KEY idx_food_review_processed_food_recent (processed_food_id, id DESC),
    ADD CONSTRAINT fk_food_review_processed_food FOREIGN KEY (processed_food_id) REFERENCES processed_food_product (id),
    ADD CONSTRAINT ck_food_review_target CHECK (
        (target_type = 'FOOD' AND food_id IS NOT NULL AND processed_food_id IS NULL)
        OR (target_type = 'PROCESSED_FOOD' AND food_id IS NULL AND processed_food_id IS NOT NULL)
    );

SET SESSION lock_wait_timeout = DEFAULT;
