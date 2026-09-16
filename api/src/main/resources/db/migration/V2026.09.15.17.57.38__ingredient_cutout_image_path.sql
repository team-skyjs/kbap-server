-- KB-567: 재료 이미지를 누끼본(배경 제거, 알파 채널 webp)으로 교체한다. 디렉터리만
-- images/webp/ingredients/ → images/webp/ingredients-cut/ 로 바뀌고 파일명·확장자는 그대로다.
-- S3·CDN 실물 81/81 HEAD 200 확인(https://d29c1cr2ng7w0.cloudfront.net, dev·prod 공용 버킷).
-- 키는 소문자여야 한다 — 대문자 키는 CDN 403 이다.
-- 스키마 변경 없이 데이터만 정정하며, 같은 값으로 여러 번 실행해도 결과가 같다(멱등).
-- 되돌리려면 같은 형태로 ingredients/ 를 다시 넣으면 된다 — 원본 파일은 S3 에 그대로 있다.
UPDATE ingredients
SET image_path = CONCAT('images/webp/ingredients-cut/', LOWER(code), '.webp')
WHERE image_path IS NULL
   OR image_path NOT LIKE 'images/webp/ingredients-cut/%';
