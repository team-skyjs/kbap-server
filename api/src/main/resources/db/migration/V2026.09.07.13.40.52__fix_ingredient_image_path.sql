-- KB-457: ingredients.image_path 경로 정정.
-- V2026.08.11.15.35.50__ingredient_image_path.sql 이 image_path 를 images/webp/{code}.webp 로 적재했으나
-- S3 실물은 images/webp/ingredients/{code}.webp 라(81/81 HEAD 200 확인) CDN 403 → 음식 상세 재료 사진 미표시.
-- 8/11 마이그레이션은 이미 공유/프로덕션에 적용돼 수정 불가 — 새 마이그레이션으로 데이터만 정정한다(스키마 변경 없음, 멱등).
UPDATE ingredients
SET image_path = CONCAT('images/webp/ingredients/', LOWER(code), '.webp')
WHERE image_path IS NULL
   OR image_path NOT LIKE 'images/webp/ingredients/%';
