-- KB-326: 온보딩 재료 선택 화면에 사진을 함께 노출한다 — 이미지 경로 컬럼 추가 + 시드.
-- S3 실물 파일명이 IngredientCode 소문자 snake_case 와 1:1 이라(images/webp/egg.webp) 코드에서 파생 적재한다.

ALTER TABLE ingredients ADD COLUMN image_path VARCHAR(255) NULL;

UPDATE ingredients SET image_path = CONCAT('images/webp/', LOWER(code), '.webp');
