-- KB-895 / R-131(a): CORIANDER 재료 그림(ingredients-cut/coriander.webp)이 S3 에 올라가(CloudFront 200 확인, 예진 승인) 경로를 채운다.
-- V2026.10.29.16.52.33 에서 비워 둔 값이다. 비어 있는 행만 채우고(가산) 다시 돌려도 무변이다(멱등). DDL 없음.
UPDATE ingredients
SET image_path = CONCAT('images/webp/ingredients-cut/', LOWER(code), '.webp')
WHERE code = 'CORIANDER'
  AND image_path IS NULL;
