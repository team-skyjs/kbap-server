-- 리뷰 식당(장소) 정보(KB-274): 카카오 지도 검색으로 고른 식당의 스냅샷.
-- 식당 선택은 선택 사항이고 검색 결과도 항목 결측이 있어 전 컬럼 NULL 허용이다.
-- 식당별 조회 기능이 없어 인덱스는 두지 않는다(필요해지면 kakao_place_id 에 추가).
ALTER TABLE `food_review`
    ADD COLUMN `place_name`      varchar(100)  NULL,
    ADD COLUMN `place_address`   varchar(200)  NULL,
    ADD COLUMN `kakao_place_id`  varchar(30)   NULL,
    ADD COLUMN `place_latitude`  decimal(10,7) NULL,
    ADD COLUMN `place_longitude` decimal(10,7) NULL;
