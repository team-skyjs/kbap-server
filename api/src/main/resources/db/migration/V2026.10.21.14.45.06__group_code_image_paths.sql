-- S-849(KB-842 후속): 새 6코드 아이콘 자산(ingredients-cut/{code}.webp)이 S3 에 올라가(CloudFront 200 확인, 예진 승인) 경로를 채운다.
-- 비어 있는 행만 채운다 — 기존 값은 건드리지 않고(가산), 다시 돌려도 무변(멱등). DDL 없음.
UPDATE ingredients
SET image_path = CONCAT('images/webp/ingredients-cut/', LOWER(code), '.webp')
WHERE code IN ('NUTS', 'CRUSTACEAN', 'MOLLUSK', 'MEAT', 'MEAT_BROTH', 'LAMB')
  AND image_path IS NULL;
