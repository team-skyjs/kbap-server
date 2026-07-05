-- KB-48: 음식 번역(음식명·설명)을 별도 테이블(food_name_translation·food_description_translation)에서
-- food 행의 단일 JSON 칼럼 2개로 이관한다. 기피성분(avoidance_substance.translations, #25)과 동형.
-- ko 원문은 food.korean_name·food.description 에 그대로 두고, JSON 에는 9개 대상 언어만(ko 키 없음) 담는다.
-- 이행 방식: 두 JSON 칼럼 추가(우선 nullable) → 빈 객체로 초기화 → ACTIVE·비어있지 않은 번역만 JSON_OBJECTAGG 로 백필 → NOT NULL 승격.
-- 레거시 두 테이블 DROP 은 백필 검증 후 US3(이 파일 하단에 이어붙임)에서 처리한다.

-- ============================================================
-- 1) food: 번역 JSON 칼럼 2개 추가 (우선 nullable — 백필 후 NOT NULL 승격)
-- ============================================================
ALTER TABLE food
    ADD COLUMN name_translations        JSON NULL AFTER description,
    ADD COLUMN description_translations JSON NULL AFTER name_translations;

-- 전 행을 빈 객체로 초기화(번역 0건 음식은 이 상태 유지 → 조회 시 ko 폴백).
UPDATE food
SET name_translations = JSON_OBJECT(),
    description_translations = JSON_OBJECT();

-- ============================================================
-- 2) 백필: 언어당 1행(정규화) → food 행의 JSON 객체(lang_code -> 값)
--    소프트삭제 정합: 앱은 status='ACTIVE' 만 조회하므로 ACTIVE 만 이행한다.
--    빈 문자열 값은 제외해 해당 언어 키를 만들지 않는다(폴백 대상 유지 — V6 의 <> '' 가드 답습).
-- ============================================================
UPDATE food f
JOIN (
    SELECT food_id, JSON_OBJECTAGG(lang_code, name) AS translations
    FROM food_name_translation
    WHERE status = 'ACTIVE' AND name <> ''
    GROUP BY food_id
) t ON f.id = t.food_id
SET f.name_translations = t.translations;

UPDATE food f
JOIN (
    SELECT food_id, JSON_OBJECTAGG(lang_code, content) AS translations
    FROM food_description_translation
    WHERE status = 'ACTIVE' AND content <> ''
    GROUP BY food_id
) t ON f.id = t.food_id
SET f.description_translations = t.translations;

-- ============================================================
-- 3) 백필 완료 후 전 행이 non-NULL(최소 빈 객체)이므로 NOT NULL 로 제약
--    (엔티티 non-null Map 계약과 일치).
-- ============================================================
ALTER TABLE food
    MODIFY COLUMN name_translations        JSON NOT NULL,
    MODIFY COLUMN description_translations JSON NOT NULL;

-- ============================================================
-- 4) 레거시 번역 테이블 DROP (US3 — 백필 검증(T013) 후 파괴적 이행)
--    두 테이블은 food(id) 를 참조하는 자식(inbound FK 없음)이라 DROP 순서 이슈 없다.
--    단일 출처를 food 행 JSON 으로 확정한다.
-- ============================================================
DROP TABLE food_name_translation;
DROP TABLE food_description_translation;
