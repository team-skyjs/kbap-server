-- KB-810 2단계(R-101, 예진 승인 2026-10-21): 리뷰 대상을 음식 | 가공식품으로 넓히는 선행 스키마(exclusive arc).
-- 엔티티·코드 무변 — 구 코드는 target_type 을 모르고 food_id 를 채워 INSERT 하므로 기본값 FOOD 로 그대로 동작한다.
-- food_id NOT NULL → NULL 은 가산 원칙의 예외(예진 승인 10/21). 기존 행 UPDATE 없음.
-- 한 ALTER 문으로 묶어 원자 적용(MySQL 8 atomic DDL) — 중간 실패 시 부분 적용 상태가 남지 않는다.
-- CHECK·FK 추가 때문에 테이블 재구축(COPY)이 일어난다 — 적용 시간은 PR 본문 실측 참고.
-- 롤백: 스키마는 유지하고 호환 코드(FOOD 만 쓰는 코드)로 되돌린다. 컬럼·제약을 지우지 않는다.

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
