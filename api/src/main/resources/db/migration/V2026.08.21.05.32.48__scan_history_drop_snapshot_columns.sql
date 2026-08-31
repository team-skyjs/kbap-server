-- KB-345: scan_history 의 스냅샷 컬럼 제거. 메뉴 이름은 food_id 로 food 마스터를 조회하고,
-- 사진(image_path)↔메뉴 연결은 orders(KB-337)가 소유한다. 세 컬럼을 읽는 코드는 없다.
ALTER TABLE `scan_history`
  DROP COLUMN `image_path`,
  DROP COLUMN `menu_name`,
  DROP COLUMN `korean_name`;
