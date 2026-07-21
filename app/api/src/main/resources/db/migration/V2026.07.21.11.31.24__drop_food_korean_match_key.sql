-- korean_match_key 생성 컬럼 제거 — 매칭 정규화는 앱 레벨(KoreanMenuNameNormalizer)로 일원화.
-- 생성 컬럼이 전체 컬럼 지정 INSERT/UPDATE(수동 운영 조작)를 막는 문제 해소.
ALTER TABLE food
    DROP INDEX idx_food_korean_match_key,
    DROP COLUMN korean_match_key;
