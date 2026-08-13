-- 리뷰 위치의 출처 구분: KAKAO_PLACE(검색에서 고른 식당) | AUTHOR_LOCATION(식당 미선택 시 동의받은 작성자 좌표) | NULL(위치 없음)
-- AUTHOR_LOCATION 이면 place_name/place_address/kakao_place_id 는 NULL 이고 좌표만 채워진다.
ALTER TABLE `food_review`
    ADD COLUMN `place_source` varchar(20) NULL;
