-- KB-869 가공식품 리뷰 맵기·당도 — 선택 입력 1~5 정수, 미입력은 NULL(0 으로 저장하지 않는다 — 평균 분모에서 빼야 하므로).
-- 음식 리뷰(target_type=FOOD)는 쓰지 않는다. 범위(1~5)는 앱 검증이 맡는다 — CHECK 는 MySQL 에서 테이블 복사(COPY)를 강제해 쓰기를 막으므로 두지 않는다.
-- NULL 허용 컬럼을 끝에 더하는 변경이라 INSTANT(메타데이터만, 재구축 없음). 구 코드는 이 컬럼을 모르므로 이 SQL 단독 선머지는 안전하다.
ALTER TABLE food_review
    ADD COLUMN spiciness_rating TINYINT NULL,
    ADD COLUMN sweetness_rating TINYINT NULL,
    ALGORITHM = INSTANT;
