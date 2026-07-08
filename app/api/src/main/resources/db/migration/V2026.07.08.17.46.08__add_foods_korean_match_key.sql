-- KB-90: food 매칭 키. 한국어 원문에서 한글 음절만 남긴 생성 저장 컬럼(스캔 정규화 규칙과 동일).
-- 기존/신규 row 자동 계산 → 백필 불필요. 정규화 매칭(FoodRepository.findFoodIdByKoreanMatchKey)의 조회 키.
ALTER TABLE food
    ADD COLUMN korean_match_key VARCHAR(255)
        GENERATED ALWAYS AS (REGEXP_REPLACE(korean_name, '[^가-힣]', '')) STORED;
CREATE INDEX idx_food_korean_match_key ON food (korean_match_key);
