-- KB-90: food 콘텐츠 완성 상태. 스캔 miss 로 생성된 음식은 INCOMPLETE 로 적재되고,
-- 레시피·설명·번역이 모두 채워져 READY 가 되어야 일반 조회(목록·상세·검색)에 노출된다.
-- 기존 row 는 완성본이므로 DEFAULT 'READY' 로 백필된다.
ALTER TABLE food
    ADD COLUMN content_status VARCHAR(20) NOT NULL DEFAULT 'READY';
CREATE INDEX idx_food_content_status ON food (content_status);
