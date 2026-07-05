-- food 컨텍스트 시드 보정: V7 은 재료 모델을 평탄화하며 inclusion_percent 를 전부 100 으로,
-- V8 은 spiciness 를 전부 0 으로 이행했다(더미). 여기서 10종 시드 음식에 대해
-- 표준 레시피 기반의 실제 포함 확률(1~100)과 맵기(0~10)를 채운다.
--
-- 확률 의미: "해당 음식 1인분에 그 회피 성분이 들어 있을 개연성". 실측이 아니라
-- 통상 레시피/변형 레시피를 반영한 추정치이며, 알레르기·회피 앱 특성상 누락보다
-- 과포함이 안전하므로 선택적·지역차·미량 성분도 낮은 확률로 포함한다.
-- 우산형 코드(FISH·BROTH·SEAFOOD 등)는 실제 그 범주가 관여할 때만 붙인다.

-- ============================================================
-- 1) 맵기(spiciness) 실제 값 (0=맵지 않음 ~ 10=매우 매움)
-- ============================================================
UPDATE food SET spiciness = 2 WHERE id = 1;   -- 된장찌개
UPDATE food SET spiciness = 6 WHERE id = 2;   -- 김치찌개
UPDATE food SET spiciness = 4 WHERE id = 3;   -- 비빔밥 (고추장)
UPDATE food SET spiciness = 1 WHERE id = 4;   -- 불고기 (달콤)
UPDATE food SET spiciness = 1 WHERE id = 5;   -- 삼겹살
UPDATE food SET spiciness = 7 WHERE id = 6;   -- 떡볶이
UPDATE food SET spiciness = 1 WHERE id = 7;   -- 김밥
UPDATE food SET spiciness = 1 WHERE id = 8;   -- 잡채
UPDATE food SET spiciness = 6 WHERE id = 9;   -- 순두부찌개
UPDATE food SET spiciness = 1 WHERE id = 10;  -- 물냉면

-- ============================================================
-- 2) food_avoidance_substance: 더미(전부 100) 시드를 실제 추정 확률로 교체
--    기존 시드 음식(1~10)의 매핑을 전부 지우고 리서치 결과를 다시 넣는다.
-- ============================================================
DELETE FROM food_avoidance_substance WHERE food_id BETWEEN 1 AND 10;

INSERT INTO food_avoidance_substance (food_id, substance_code, inclusion_percent, status, created_at, updated_at) VALUES
-- 1) 된장찌개: 된장·두부(대두), 마늘·파·양파, 감자, 멸치/육수, 조개, 소고기, (된장·간장의) 밀
(1, 'SOY',              100, 'ACTIVE', NOW(6), NOW(6)),
(1, 'GARLIC',            96, 'ACTIVE', NOW(6), NOW(6)),
(1, 'SCALLION',          90, 'ACTIVE', NOW(6), NOW(6)),
(1, 'ONION',             82, 'ACTIVE', NOW(6), NOW(6)),
(1, 'BROTH',             70, 'ACTIVE', NOW(6), NOW(6)),
(1, 'POTATO',            68, 'ACTIVE', NOW(6), NOW(6)),
(1, 'ANCHOVY',           58, 'ACTIVE', NOW(6), NOW(6)),
(1, 'FISH',              58, 'ACTIVE', NOW(6), NOW(6)),
(1, 'DASHI',             30, 'ACTIVE', NOW(6), NOW(6)),
(1, 'CLAM',              28, 'ACTIVE', NOW(6), NOW(6)),
(1, 'SHORT_NECK_CLAM',   24, 'ACTIVE', NOW(6), NOW(6)),
(1, 'WHEAT',             20, 'ACTIVE', NOW(6), NOW(6)),
(1, 'BEEF',              18, 'ACTIVE', NOW(6), NOW(6)),
(1, 'SHRIMP',            15, 'ACTIVE', NOW(6), NOW(6)),
-- 2) 김치찌개: 김치(마늘·파·젓갈·새우젓·멸치), 돼지고기(또는 참치), 두부, 양파, 멸치육수
(2, 'GARLIC',            97, 'ACTIVE', NOW(6), NOW(6)),
(2, 'SCALLION',          92, 'ACTIVE', NOW(6), NOW(6)),
(2, 'PORK',              85, 'ACTIVE', NOW(6), NOW(6)),
(2, 'FISH_SAUCE',        82, 'ACTIVE', NOW(6), NOW(6)),
(2, 'SOY',               80, 'ACTIVE', NOW(6), NOW(6)),
(2, 'ONION',             75, 'ACTIVE', NOW(6), NOW(6)),
(2, 'FISH',              70, 'ACTIVE', NOW(6), NOW(6)),
(2, 'ANCHOVY',           65, 'ACTIVE', NOW(6), NOW(6)),
(2, 'BROTH',             60, 'ACTIVE', NOW(6), NOW(6)),
(2, 'SALTED_SHRIMP',     60, 'ACTIVE', NOW(6), NOW(6)),
(2, 'SHRIMP',            45, 'ACTIVE', NOW(6), NOW(6)),
(2, 'OYSTER',            22, 'ACTIVE', NOW(6), NOW(6)),
(2, 'TUNA',              20, 'ACTIVE', NOW(6), NOW(6)),
(2, 'WHEAT',             15, 'ACTIVE', NOW(6), NOW(6)),
-- 3) 비빔밥: 계란, 참기름·깨, 고추장(대두·밀·보리), 나물(콩나물·시금치·당근), 소고기, 마늘
(3, 'SOY',               95, 'ACTIVE', NOW(6), NOW(6)),
(3, 'SESAME',            92, 'ACTIVE', NOW(6), NOW(6)),
(3, 'EGG',               90, 'ACTIVE', NOW(6), NOW(6)),
(3, 'GARLIC',            80, 'ACTIVE', NOW(6), NOW(6)),
(3, 'CARROT',            78, 'ACTIVE', NOW(6), NOW(6)),
(3, 'BEEF',              55, 'ACTIVE', NOW(6), NOW(6)),
(3, 'WHEAT',             55, 'ACTIVE', NOW(6), NOW(6)),
(3, 'SCALLION',          45, 'ACTIVE', NOW(6), NOW(6)),
(3, 'BARLEY',            35, 'ACTIVE', NOW(6), NOW(6)),
(3, 'ONION',             30, 'ACTIVE', NOW(6), NOW(6)),
-- 4) 불고기: 소고기, 간장(대두·밀), 참기름·깨, 마늘·양파·파, 맛술/미림(알코올)
(4, 'BEEF',             100, 'ACTIVE', NOW(6), NOW(6)),
(4, 'SOY',               98, 'ACTIVE', NOW(6), NOW(6)),
(4, 'GARLIC',            92, 'ACTIVE', NOW(6), NOW(6)),
(4, 'SESAME',            90, 'ACTIVE', NOW(6), NOW(6)),
(4, 'ONION',             88, 'ACTIVE', NOW(6), NOW(6)),
(4, 'WHEAT',             85, 'ACTIVE', NOW(6), NOW(6)),
(4, 'SCALLION',          80, 'ACTIVE', NOW(6), NOW(6)),
(4, 'ALCOHOL',           45, 'ACTIVE', NOW(6), NOW(6)),
(4, 'MIRIN',             42, 'ACTIVE', NOW(6), NOW(6)),
(4, 'COOKING_WINE',      40, 'ACTIVE', NOW(6), NOW(6)),
-- 5) 삼겹살: 돼지고기, 구운마늘·쌈장(된장·고추장), 기름장(참기름), 파채, 새우젓(찍먹)
(5, 'PORK',             100, 'ACTIVE', NOW(6), NOW(6)),
(5, 'GARLIC',            88, 'ACTIVE', NOW(6), NOW(6)),
(5, 'SESAME',            80, 'ACTIVE', NOW(6), NOW(6)),
(5, 'SOY',               78, 'ACTIVE', NOW(6), NOW(6)),
(5, 'SCALLION',          75, 'ACTIVE', NOW(6), NOW(6)),
(5, 'SALTED_SHRIMP',     45, 'ACTIVE', NOW(6), NOW(6)),
(5, 'SHRIMP',            40, 'ACTIVE', NOW(6), NOW(6)),
(5, 'WHEAT',             40, 'ACTIVE', NOW(6), NOW(6)),
(5, 'ONION',             30, 'ACTIVE', NOW(6), NOW(6)),
-- 6) 떡볶이: 밀떡·라면사리(밀), 고추장(대두·밀·보리), 어묵(생선·밀), 파·양파·마늘, 멸치육수, 삶은계란
(6, 'WHEAT',             88, 'ACTIVE', NOW(6), NOW(6)),
(6, 'SOY',               85, 'ACTIVE', NOW(6), NOW(6)),
(6, 'FISH',              85, 'ACTIVE', NOW(6), NOW(6)),
(6, 'SCALLION',          85, 'ACTIVE', NOW(6), NOW(6)),
(6, 'GARLIC',            75, 'ACTIVE', NOW(6), NOW(6)),
(6, 'ONION',             70, 'ACTIVE', NOW(6), NOW(6)),
(6, 'BROTH',             65, 'ACTIVE', NOW(6), NOW(6)),
(6, 'ANCHOVY',           60, 'ACTIVE', NOW(6), NOW(6)),
(6, 'EGG',               55, 'ACTIVE', NOW(6), NOW(6)),
(6, 'BARLEY',            40, 'ACTIVE', NOW(6), NOW(6)),
(6, 'SQUID',             20, 'ACTIVE', NOW(6), NOW(6)),
-- 7) 김밥: 계란, 참기름·깨, 당근, 햄(돼지), 맛살(게·생선·밀), 단무지, 소고기/참치/치즈 변형
(7, 'EGG',               92, 'ACTIVE', NOW(6), NOW(6)),
(7, 'SESAME',            88, 'ACTIVE', NOW(6), NOW(6)),
(7, 'CARROT',            85, 'ACTIVE', NOW(6), NOW(6)),
(7, 'PORK',              70, 'ACTIVE', NOW(6), NOW(6)),
(7, 'FISH',              50, 'ACTIVE', NOW(6), NOW(6)),
(7, 'WHEAT',             45, 'ACTIVE', NOW(6), NOW(6)),
(7, 'CRAB',              45, 'ACTIVE', NOW(6), NOW(6)),
(7, 'TUNA',              30, 'ACTIVE', NOW(6), NOW(6)),
(7, 'BEEF',              25, 'ACTIVE', NOW(6), NOW(6)),
(7, 'CHEESE',            15, 'ACTIVE', NOW(6), NOW(6)),
(7, 'MILK',              15, 'ACTIVE', NOW(6), NOW(6)),
(7, 'DAIRY',             15, 'ACTIVE', NOW(6), NOW(6)),
-- 8) 잡채: 간장(대두·밀), 참기름·깨, 당근·양파·마늘·파, 소고기, 계란지단, 버섯·시금치
(8, 'SOY',               95, 'ACTIVE', NOW(6), NOW(6)),
(8, 'SESAME',            90, 'ACTIVE', NOW(6), NOW(6)),
(8, 'CARROT',            85, 'ACTIVE', NOW(6), NOW(6)),
(8, 'WHEAT',             82, 'ACTIVE', NOW(6), NOW(6)),
(8, 'ONION',             80, 'ACTIVE', NOW(6), NOW(6)),
(8, 'GARLIC',            75, 'ACTIVE', NOW(6), NOW(6)),
(8, 'BEEF',              65, 'ACTIVE', NOW(6), NOW(6)),
(8, 'SCALLION',          55, 'ACTIVE', NOW(6), NOW(6)),
(8, 'EGG',               50, 'ACTIVE', NOW(6), NOW(6)),
-- 9) 순두부찌개: 순두부(대두), 계란, 마늘·파·양파, 해물(조개·바지락·새우·굴·오징어), 멸치육수, 돼지고기
(9, 'SOY',              100, 'ACTIVE', NOW(6), NOW(6)),
(9, 'GARLIC',            92, 'ACTIVE', NOW(6), NOW(6)),
(9, 'SCALLION',          88, 'ACTIVE', NOW(6), NOW(6)),
(9, 'EGG',               75, 'ACTIVE', NOW(6), NOW(6)),
(9, 'BROTH',             68, 'ACTIVE', NOW(6), NOW(6)),
(9, 'ONION',             65, 'ACTIVE', NOW(6), NOW(6)),
(9, 'FISH',              60, 'ACTIVE', NOW(6), NOW(6)),
(9, 'ANCHOVY',           55, 'ACTIVE', NOW(6), NOW(6)),
(9, 'CLAM',              55, 'ACTIVE', NOW(6), NOW(6)),
(9, 'SEAFOOD',           55, 'ACTIVE', NOW(6), NOW(6)),
(9, 'SHORT_NECK_CLAM',   50, 'ACTIVE', NOW(6), NOW(6)),
(9, 'SHRIMP',            50, 'ACTIVE', NOW(6), NOW(6)),
(9, 'SQUID',             40, 'ACTIVE', NOW(6), NOW(6)),
(9, 'OYSTER',            35, 'ACTIVE', NOW(6), NOW(6)),
(9, 'PORK',              35, 'ACTIVE', NOW(6), NOW(6)),
(9, 'MUSSEL',            25, 'ACTIVE', NOW(6), NOW(6)),
-- 10) 물냉면: 메밀면(메밀·밀), 육수(소고기·편육), 삶은계란, 겨자
(10, 'BUCKWHEAT',        95, 'ACTIVE', NOW(6), NOW(6)),
(10, 'BROTH',            90, 'ACTIVE', NOW(6), NOW(6)),
(10, 'EGG',              85, 'ACTIVE', NOW(6), NOW(6)),
(10, 'BEEF',             82, 'ACTIVE', NOW(6), NOW(6)),
(10, 'MUSTARD',          60, 'ACTIVE', NOW(6), NOW(6)),
(10, 'WHEAT',            55, 'ACTIVE', NOW(6), NOW(6)),
(10, 'PORK',             25, 'ACTIVE', NOW(6), NOW(6));
