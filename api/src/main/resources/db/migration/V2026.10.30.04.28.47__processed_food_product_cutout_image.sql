-- KB-911: 가공식품 누끼(배경 제거 투명 PNG) 이미지 URL. image_url 은 HACCP 미러 동기화가 매번 덮어쓰므로 별도 컬럼에 둔다.
-- 어드민 presigned 업로드 후 confirm 으로만 채워지고 미러 동기화는 건드리지 않는다. 값이 있으면 API imageUrl 이 이 값을 우선한다.
ALTER TABLE processed_food_product
    ADD COLUMN cutout_image_url VARCHAR(500) NULL AFTER meta_image_url;
