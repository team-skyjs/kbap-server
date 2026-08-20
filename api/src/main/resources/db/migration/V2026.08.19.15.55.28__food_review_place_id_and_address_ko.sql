-- KB-350: 리뷰 위치에 Google placeId(언어 무관 가게 식별자)와 한국어 주소 슬롯을 더한다.
-- place_id 는 GOOGLE_PLACE 출처일 때 검색 응답의 places.id 를 그대로 저장한다(name/address 는 작성자 검색 언어 스냅샷).
-- place_address_ko 는 지금은 항상 NULL — 백오피스가 place_id 로 한국어 주소를 조회해 채우는 후속 경로용 선점 컬럼.
ALTER TABLE `food_review`
    ADD COLUMN `place_id` varchar(255) NULL,
    ADD COLUMN `place_address_ko` varchar(200) NULL;
