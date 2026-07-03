-- food 컨텍스트: 재료(food_ingredient·ingredient·ingredient_avoidance_substance) 기반 매핑을
-- 음식↔기피성분 직접 매핑(food_avoidance_substance)으로 대체한다.
-- junction 이 substance_code 를 직접 보유해 avoidance_substance 추가 조인 없이 코드를 확보한다(N+1 제거).
-- 공통 컬럼(status=EntityStatus 소프트삭제, created_at, updated_at)은 BaseEntity(@MappedSuperclass)에서 온다.
CREATE TABLE food_avoidance_substance (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    food_id           BIGINT      NOT NULL,
    substance_code    VARCHAR(40) NOT NULL,
    inclusion_percent INT         NOT NULL,             -- 1~100 (포함 확률); 응답 정렬은 서비스단에서 이 값 내림차순
    status            VARCHAR(20) NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fas_food FOREIGN KEY (food_id) REFERENCES food(id),
    CONSTRAINT fk_fas_substance FOREIGN KEY (substance_code) REFERENCES avoidance_substance(code),
    UNIQUE KEY uq_food_avoidance_substance (food_id, substance_code),
    CONSTRAINT ck_fas_inclusion_percent CHECK (inclusion_percent BETWEEN 1 AND 100)
);

-- 시드 이행: 기존 재료 기반 매핑(food_ingredient → ingredient_avoidance_substance)을
-- 음식↔기피성분 직접 매핑으로 평탄화한다. 재료 단계 확률은 소실되므로 100 으로 이행한다.
INSERT INTO food_avoidance_substance (food_id, substance_code, inclusion_percent, status, created_at, updated_at)
SELECT DISTINCT fi.food_id, s.code, 100, 'ACTIVE', NOW(), NOW()
FROM food_ingredient fi
JOIN ingredient_avoidance_substance ias ON ias.ingredient_id = fi.ingredient_id
JOIN avoidance_substance s ON s.id = ias.substance_id
WHERE fi.status = 'ACTIVE' AND ias.status = 'ACTIVE' AND s.status = 'ACTIVE';

-- 재료/레시피 모델 제거: 음식↔기피성분 직접 매핑으로 대체돼 더 이상 쓰지 않는다. FK 역순으로 DROP.
DROP TABLE food_ingredient;
DROP TABLE ingredient_avoidance_substance;
DROP TABLE ingredient_name_translation;
DROP TABLE ingredient;
