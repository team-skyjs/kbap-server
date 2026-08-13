-- 리뷰 위치 정보(KB-274): 카카오 지도 검색으로 고른 식당 스냅샷 / 사용자 입력 식당명 / 작성자 좌표.
-- 위치 저장은 선택 사항이고 형태별 결측이 있어 전 컬럼 NULL 허용이다.
-- 외부 지도 제공자 식별자(kakao place id 등)는 저장하지 않는다 — 딥링크·클러스터링은 명·주소·좌표로 충분.
-- 식당별 조회 기능이 없어 인덱스는 두지 않는다.
ALTER TABLE `food_review`
    ADD COLUMN `place_name`      varchar(100)  NULL,
    ADD COLUMN `place_address`   varchar(200)  NULL,
    ADD COLUMN `place_latitude`  decimal(10,7) NULL,
    ADD COLUMN `place_longitude` decimal(10,7) NULL;
