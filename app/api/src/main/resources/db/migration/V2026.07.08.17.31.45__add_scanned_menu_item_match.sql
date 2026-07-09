-- KB-90: 스캔 항목에 정제·매칭 결과 컬럼 추가.
-- match_status = MenuItemMatch: MATCHED/PENDING/NOT_FOOD. matched_food_id 는 MATCHED/PENDING 일 때 채워진다(PENDING 은 미완성 food row).
ALTER TABLE scanned_menu_item
    ADD COLUMN match_status   VARCHAR(20) NOT NULL DEFAULT 'NOT_FOOD',
    ADD COLUMN matched_food_id BIGINT     NULL;
