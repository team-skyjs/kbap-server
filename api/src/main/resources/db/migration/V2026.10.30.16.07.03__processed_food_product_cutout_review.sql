-- KB-922: 가공식품 누끼 검수. 일괄 등록된 누끼는 PENDING 으로 들어가고, APPROVED 일 때만 API imageUrl 이 누끼를 쓴다.
-- cutout_review_status: PENDING·APPROVED·REJECTED (NULL = 누끼 없음). cutout_route: vision·fallback(누끼 생성 경로). cutout_coverage: 전경 비율 0~1.
-- 검수 이력은 food.human_reviewed_by/at 선례처럼 최신 검수자·시각·사유(반려 사유를 어드민 화면에 보인다)만 둔다.
-- 가산만: 컬럼 추가가 INSTANT 로 끝나도록 위치 지정(AFTER) 없이 쓰고 인덱스·FK 와 문장을 나눈다. 기존 값은 바꾸지 않는다. 구 코드는 이 컬럼을 모른다.
ALTER TABLE processed_food_product
    ADD COLUMN cutout_review_status VARCHAR(16) NULL,
    ADD COLUMN cutout_route VARCHAR(16) NULL,
    ADD COLUMN cutout_coverage DECIMAL(5,4) NULL,
    ADD COLUMN cutout_reviewed_at DATETIME(6) NULL,
    ADD COLUMN cutout_reviewed_by BIGINT NULL,
    ADD COLUMN cutout_review_reason VARCHAR(200) NULL;

-- FK 추가는 foreign_key_checks 가 켜져 있으면 COPY(테이블 재작성·쓰기 차단)로만 돈다. 새 컬럼은 전부 NULL 이라 검사할 값이 없으므로 이 문장만 끄고 INPLACE 로 둔다.
SET foreign_key_checks = 0;

ALTER TABLE processed_food_product
    ADD INDEX idx_processed_food_product_cutout_review (cutout_review_status, cutout_route, cutout_coverage),
    ADD CONSTRAINT fk_processed_food_product_cutout_reviewed_by FOREIGN KEY (cutout_reviewed_by) REFERENCES admin_account (id);

SET foreign_key_checks = 1;
