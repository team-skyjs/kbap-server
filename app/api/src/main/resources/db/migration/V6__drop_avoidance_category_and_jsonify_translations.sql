-- 009: 기피 성분 번역을 언어별 컬럼(name_*) → 단일 translations JSON(키=LanguageCode.code, 비-ko)로 이관.
-- category 테이블 DROP 은 US2(같은 파일에 이어붙임)에서 처리한다. V5 는 수정 금지.

ALTER TABLE avoidance_substance
    ADD COLUMN translations JSON NULL;

-- 백필: 전 행을 빈 객체로 초기화한 뒤 비-NULL 언어만 키로 추가(NULL 언어는 키 부재).
UPDATE avoidance_substance
SET translations = JSON_OBJECT();

UPDATE avoidance_substance SET translations = JSON_SET(translations, '$."en"', name_en) WHERE name_en IS NOT NULL AND name_en <> '';
UPDATE avoidance_substance SET translations = JSON_SET(translations, '$."ja"', name_ja) WHERE name_ja IS NOT NULL AND name_ja <> '';
UPDATE avoidance_substance SET translations = JSON_SET(translations, '$."zh-Hans"', name_zh_hans) WHERE name_zh_hans IS NOT NULL AND name_zh_hans <> '';
UPDATE avoidance_substance SET translations = JSON_SET(translations, '$."zh-Hant"', name_zh_hant) WHERE name_zh_hant IS NOT NULL AND name_zh_hant <> '';
UPDATE avoidance_substance SET translations = JSON_SET(translations, '$."vi"', name_vi) WHERE name_vi IS NOT NULL AND name_vi <> '';
UPDATE avoidance_substance SET translations = JSON_SET(translations, '$."id"', name_id) WHERE name_id IS NOT NULL AND name_id <> '';
UPDATE avoidance_substance SET translations = JSON_SET(translations, '$."th"', name_th) WHERE name_th IS NOT NULL AND name_th <> '';
UPDATE avoidance_substance SET translations = JSON_SET(translations, '$."ru"', name_ru) WHERE name_ru IS NOT NULL AND name_ru <> '';
UPDATE avoidance_substance SET translations = JSON_SET(translations, '$."es"', name_es) WHERE name_es IS NOT NULL AND name_es <> '';

-- 백필 완료 후 전 행이 non-NULL 이므로 NOT NULL 로 제약(엔티티 non-null Map 계약과 일치).
ALTER TABLE avoidance_substance
    MODIFY translations JSON NOT NULL;

ALTER TABLE avoidance_substance
    DROP COLUMN name_zh_hans,
    DROP COLUMN name_en,
    DROP COLUMN name_ja,
    DROP COLUMN name_zh_hant,
    DROP COLUMN name_vi,
    DROP COLUMN name_id,
    DROP COLUMN name_th,
    DROP COLUMN name_ru,
    DROP COLUMN name_es;

-- 3분류 카테고리 완전 제거(도메인 소비자 없음). ingredient_avoidance_substance 는
-- avoidance_substance(id) 만 참조하므로 인입 FK 없어 DROP 안전.
DROP TABLE avoidance_substance_category;
