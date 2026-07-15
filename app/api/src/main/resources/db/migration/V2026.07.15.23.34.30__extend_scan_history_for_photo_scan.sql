-- KB-138: 사진 스캔 전환 — scan_history 를 추출 항목 단위(항목당 1 row)로 확장한다.
-- image_path·menu_name·korean_name 은 신규 필수 컬럼(기존 로컬 row 는 빈 문자열로 채운 뒤 기본값 제거),
-- price 는 KRW 정수(미표기 null), food_id 는 미매칭 항목도 기록하도록 NULL 허용으로 완화한다.
ALTER TABLE scan_history
    ADD COLUMN image_path  VARCHAR(512) NOT NULL DEFAULT '' AFTER member_id,
    ADD COLUMN menu_name   VARCHAR(100) NOT NULL DEFAULT '' AFTER image_path,
    ADD COLUMN korean_name VARCHAR(100) NOT NULL DEFAULT '' AFTER menu_name,
    ADD COLUMN price       INT          NULL       AFTER korean_name;

ALTER TABLE scan_history
    ALTER COLUMN image_path DROP DEFAULT,
    ALTER COLUMN menu_name DROP DEFAULT,
    ALTER COLUMN korean_name DROP DEFAULT;

ALTER TABLE scan_history
    MODIFY COLUMN food_id BIGINT NULL;
