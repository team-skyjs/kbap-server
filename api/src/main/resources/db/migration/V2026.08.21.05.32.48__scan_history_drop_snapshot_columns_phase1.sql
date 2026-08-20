-- KB-345: scan_history 의 스냅샷 컬럼(image_path·menu_name·korean_name) 사용 중단.
-- 메뉴 이름은 food_id 로 food 마스터를 조회하고, 사진↔메뉴 연결은 orders(KB-337)가 소유한다.
-- expand/contract 1단계: 구 리비전이 배포 창에서 이 컬럼들에 INSERT 해도 깨지지 않도록 nullable 로만 완화한다.
-- 물리 DROP 은 신 코드 전면 배포 후 별도 마이그레이션(2단계)에서 수행한다.
ALTER TABLE `scan_history`
  MODIFY COLUMN `image_path` varchar(512) NULL,
  MODIFY COLUMN `menu_name` varchar(100) NULL,
  MODIFY COLUMN `korean_name` varchar(100) NULL;
