-- KB-637: 주문 항목 썸네일을 회원이 찍은 사진으로 바꿀 수 있게 한다.
-- NULL = 회원 사진 없음 → 기존처럼 food_id 의 카탈로그 대표 이미지를 쓴다. 기본 사진으로 되돌리기도 NULL 로 돌린다.
-- 값은 도메인 없는 오브젝트 경로(uploaded_image.object_path 와 같은 형식, 용도 ORDER_ITEM = images/orders/).
-- 가산만: 기존 행은 NULL 로 남고 구 코드는 이 컬럼을 모른다.
ALTER TABLE `order_item`
  ADD COLUMN `image_path` varchar(512) NULL;
