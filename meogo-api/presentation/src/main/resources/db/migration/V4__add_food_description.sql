-- V4__add_food_description.sql
-- food 컨텍스트에 음식 설명 2종(간단·자세)과 그 다국어 번역을 더한다(가산적 변경, V1~V3 불변).
-- ko 원문은 food.brief_description / detailed_description 컬럼에, 9개 대상 언어 번역은
-- food_description_translation 에 둔다(ko 는 번역 테이블에 저장하지 않는다 — 음식명 번역과 동일 정책).
-- 본 마이그레이션의 설명 콘텐츠는 mock placeholder 다(실제 편집 콘텐츠는 기획 확정 시 후속 반영).

-- ============================================================
-- 1) food 에 설명 컬럼 추가 (우선 nullable 로 추가 — 기존 row 채운 뒤 NOT NULL 강화)
-- ============================================================
ALTER TABLE food
    ADD COLUMN brief_description    VARCHAR(255)  NULL AFTER image_ref,
    ADD COLUMN detailed_description VARCHAR(1024) NULL AFTER brief_description;

-- ============================================================
-- 2) V3 seed 음식(10종) ko 설명 채움 (mock placeholder)
-- ============================================================
UPDATE food SET
    brief_description    = '구수한 한국식 된장찌개',
    detailed_description = '된장찌개는 된장을 풀어 두부·애호박·감자 등을 넣고 끓인 한국의 대표적인 찌개로, 깊고 구수한 맛이 특징이다.'
WHERE korean_name = '된장찌개';

UPDATE food SET
    brief_description    = '얼큰한 김치찌개',
    detailed_description = '김치찌개는 잘 익은 김치에 돼지고기와 두부를 넣고 끓인 찌개로, 칼칼하고 얼큰한 국물이 매력적인 한국의 국민 음식이다.'
WHERE korean_name = '김치찌개';

UPDATE food SET
    brief_description    = '나물을 비벼 먹는 비빔밥',
    detailed_description = '비빔밥은 밥 위에 여러 나물과 계란, 고추장을 올려 함께 비벼 먹는 음식으로, 색색의 재료가 어우러진 건강한 한 그릇 요리다.'
WHERE korean_name = '비빔밥';

UPDATE food SET
    brief_description    = '달콤한 양념 불고기',
    detailed_description = '불고기는 얇게 썬 소고기를 간장·설탕·마늘로 만든 양념에 재워 구운 음식으로, 달콤짭짤한 맛이 외국인에게도 인기가 많다.'
WHERE korean_name = '불고기';

UPDATE food SET
    brief_description    = '노릇하게 구운 삼겹살',
    detailed_description = '삼겹살은 돼지고기 뱃살 부위를 불판에 노릇하게 구워 쌈이나 마늘과 함께 먹는 한국식 구이로, 회식과 외식에서 가장 사랑받는 메뉴다.'
WHERE korean_name = '삼겹살';

UPDATE food SET
    brief_description    = '매콤달콤 떡볶이',
    detailed_description = '떡볶이는 가래떡을 고추장 양념에 어묵·대파와 함께 볶은 분식으로, 매콤달콤한 맛이 특징인 한국의 대표 길거리 음식이다.'
WHERE korean_name = '떡볶이';

UPDATE food SET
    brief_description    = '한 입 크기 김밥',
    detailed_description = '김밥은 밥과 단무지·당근·계란·햄 등을 김에 말아 한 입 크기로 썰어 먹는 음식으로, 간편하면서도 든든한 한 끼로 사랑받는다.'
WHERE korean_name = '김밥';

UPDATE food SET
    brief_description    = '쫄깃한 당면 잡채',
    detailed_description = '잡채는 당면을 소고기·시금치·당근·버섯 등 여러 재료와 함께 간장 양념에 볶은 음식으로, 쫄깃한 식감과 은은한 단맛이 일품인 잔치 음식이다.'
WHERE korean_name = '잡채';

UPDATE food SET
    brief_description    = '부드러운 순두부찌개',
    detailed_description = '순두부찌개는 부드러운 순두부에 계란과 고춧가루를 넣고 얼큰하게 끓인 찌개로, 보들보들한 두부와 칼칼한 국물의 조화가 좋다.'
WHERE korean_name = '순두부찌개';

UPDATE food SET
    brief_description    = '시원한 물냉면',
    detailed_description = '물냉면은 메밀면을 차가운 육수에 말아 오이·계란·소고기를 올려 먹는 면 요리로, 더운 여름에 특히 사랑받는 시원한 한국 음식이다.'
WHERE korean_name = '물냉면';

-- ============================================================
-- 3) 음식 설명 번역 테이블 생성
--    ko 는 저장하지 않는다. lang_code 는 9개 대상 언어만, kind 는 BRIEF/DETAILED 만 허용.
--    content 는 간단·자세 공용 단일 컬럼이라 자세(1024) 길이에 맞춘다.
-- ============================================================
CREATE TABLE food_description_translation (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    food_id    BIGINT        NOT NULL,
    kind       VARCHAR(10)   NOT NULL,                  -- BRIEF | DETAILED
    lang_code  VARCHAR(10)   NOT NULL,                  -- zh-Hans·en·ja·zh-Hant·vi·id·th·ru·es
    content    VARCHAR(1024) NOT NULL,
    status     VARCHAR(20)   NOT NULL,                  -- EntityStatus: ACTIVE/DELETED
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fdt_food FOREIGN KEY (food_id) REFERENCES food(id),
    UNIQUE KEY uq_fdt (food_id, kind, lang_code),
    CONSTRAINT ck_fdt_kind CHECK (kind IN ('BRIEF', 'DETAILED')),
    CONSTRAINT ck_fdt_lang CHECK (lang_code IN ('zh-Hans','en','ja','zh-Hant','vi','id','th','ru','es'))
);

-- ============================================================
-- 4) 음식 × {BRIEF, DETAILED} × 9개 언어 번역 INSERT (mock placeholder)
--    각 음식의 9개 언어 음식명 번역(food_name_translation)을 기준으로 모든 대상 언어를
--    빠짐없이 채운다(음식 10종 × 9개 언어 × 2종 = 180행).
-- ============================================================
INSERT INTO food_description_translation (food_id, kind, lang_code, content, status, created_at, updated_at)
SELECT food_id, 'BRIEF', lang_code,
       CONCAT('[', lang_code, '] Brief description of ', name),
       'ACTIVE', NOW(6), NOW(6)
FROM food_name_translation;

INSERT INTO food_description_translation (food_id, kind, lang_code, content, status, created_at, updated_at)
SELECT food_id, 'DETAILED', lang_code,
       CONCAT('[', lang_code, '] Detailed description of ', name),
       'ACTIVE', NOW(6), NOW(6)
FROM food_name_translation;

-- ============================================================
-- 5) 모든 음식의 설명 ko 원문이 채워졌으므로 NOT NULL 로 강화
-- ============================================================
ALTER TABLE food
    MODIFY COLUMN brief_description    VARCHAR(255)  NOT NULL,
    MODIFY COLUMN detailed_description VARCHAR(1024) NOT NULL;
