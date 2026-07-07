-- KB-96: 음식 candidate 스테이징 테이블.
-- 미완성 음식(성분·번역이 부분적으로만 채워질 수 있음)을 서빙 테이블(food)과 분리해 보관한다.
-- 완성 조건(성분 매핑 有 && ko 설명 有 && 9개 대상 언어 번역 완비 && 미승격)을 만족한 것만 승격 배치가 food 로 적재한다.
-- 성분 매핑은 자식 테이블이 아니라 JSON 스냅샷([{code, percent}])으로 둔다(ADR-0012 / data-model R1).
-- 길이·타입은 MySQL 기준(H2 미고려). description 255 = FoodContent.MAX_DESCRIPTION_LENGTH 정합.

CREATE TABLE food_candidate (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    korean_name              VARCHAR(255) NOT NULL,
    description              VARCHAR(255) NULL,
    description_translations JSON         NOT NULL,
    substance_mapping        JSON         NOT NULL,
    published_food_id        BIGINT       NULL,
    status                   VARCHAR(20)  NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_food_candidate_korean_name (korean_name),
    KEY idx_food_candidate_promotable (published_food_id)
);
