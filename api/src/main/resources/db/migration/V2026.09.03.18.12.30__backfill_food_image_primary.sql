-- 기존 food.image_ref(현재 살아있는 대표 이미지)를 food_image 의 primary 행으로 백필한다.
-- 과거 image_batch_item 이력은 미검수·S3 부재 가능성이 있어 가져오지 않는다(스펙 §2.4).
-- 이미 갤러리 행이 있는 음식은 건너뛴다(멱등).
INSERT INTO food_image (food_id, image_key, is_primary, source, sort_order, status, created_at, updated_at)
SELECT f.id, f.image_ref, 1, 'GENERATED', 0, 'ACTIVE', NOW(6), NOW(6)
FROM food f
WHERE f.image_ref IS NOT NULL
  AND f.image_ref <> ''
  AND NOT EXISTS (SELECT 1 FROM food_image fi WHERE fi.food_id = f.id);
