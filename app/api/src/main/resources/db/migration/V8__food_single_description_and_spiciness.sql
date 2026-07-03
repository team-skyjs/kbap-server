-- food 컨텍스트: 간단·자세 2종 설명(brief/detailed)을 단일 설명(description)으로 압축하고 맵기(spiciness)를 추가한다.
-- 설명 검증 책임은 도메인 Value Object(FoodDescription, 255자)로 이관되므로 컬럼 길이도 255 로 정합시킨다.
-- spiciness 는 0~10 정수(0=맵지 않음, 10=매우 매움) — 기존 seed 데이터는 합리적 기본값 0(맵지 않음)으로 채운다.

-- ============================================================
-- 1) food: 단일 description·spiciness 추가 (우선 nullable — 기존 row 채운 뒤 NOT NULL 강화)
-- ============================================================
ALTER TABLE food
    ADD COLUMN description VARCHAR(255) NULL AFTER image_ref,
    ADD COLUMN spiciness   INT          NULL AFTER description;

-- 이행 규칙: brief_description 이 있으면 그대로, 없으면 detailed_description 을 255자로 잘라 사용.
UPDATE food
SET description = CASE
        WHEN brief_description IS NOT NULL AND TRIM(brief_description) <> '' THEN brief_description
        ELSE LEFT(detailed_description, 255)
    END;

UPDATE food SET spiciness = 0;

ALTER TABLE food
    MODIFY COLUMN description VARCHAR(255) NOT NULL,
    MODIFY COLUMN spiciness   INT          NOT NULL;

ALTER TABLE food
    ADD CONSTRAINT ck_food_spiciness CHECK (spiciness BETWEEN 0 AND 10);

ALTER TABLE food
    DROP COLUMN brief_description,
    DROP COLUMN detailed_description;

-- ============================================================
-- 2) food_description_translation: 종류(kind) 개념 제거 → (food_id, lang_code) 단일 설명 번역.
--    BRIEF 번역만 남기고(음식 표시 설명 기준) DETAILED 는 버린다. content 길이도 255 로 정합.
-- ============================================================
DELETE FROM food_description_translation WHERE kind = 'DETAILED';

-- 새 유일 제약(food_id, lang_code)을 먼저 만든다. food_id 가 선두라 FK(fk_fdt_food)의 백킹 인덱스도 겸한다.
-- 기존 uq_fdt(food_id, kind, lang_code)가 FK 백킹 인덱스라 먼저 드롭할 수 없으므로(신규 인덱스 생성 후 드롭).
-- DETAILED 를 이미 지워 (food_id, lang_code)당 BRIEF 1행만 남아 유일 제약을 만족한다.
ALTER TABLE food_description_translation
    ADD CONSTRAINT uq_fdt_food_lang UNIQUE (food_id, lang_code);
ALTER TABLE food_description_translation DROP INDEX uq_fdt;

ALTER TABLE food_description_translation DROP CHECK ck_fdt_kind;
ALTER TABLE food_description_translation DROP COLUMN kind;

UPDATE food_description_translation SET content = LEFT(content, 255);
ALTER TABLE food_description_translation MODIFY COLUMN content VARCHAR(255) NOT NULL;
