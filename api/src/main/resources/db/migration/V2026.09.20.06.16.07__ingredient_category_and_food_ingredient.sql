-- KB-594: 재료 분류·음식-재료 관계 테이블 (짝 A — 스키마 선행). 전부 가산 변경이라 구 코드와 공존한다.
-- 설계 정본: spec/specs/001-personalized-menu-mvp/ingredient-category-db-plan.md v2 (2026-09-20 확정).
-- 이 파일은 짝 B(KB-595 이중 쓰기) PR 에 byte 단위로 같게 동봉한다 — Flyway 체크섬은 주석까지 포함한다.

-- 탐색용 대분류. 재료당 분류 1개라 매핑 테이블 없이 ingredients.category_id 로 붙는다.
-- code 는 외부 계약용 불변 식별자라 ascii 로 고정한다. 한국어 이름은 korean_name, 외국어는 translations
-- (ingredients 와 같은 규약 — translations 에 ko 를 두지 않는다).
CREATE TABLE `ingredient_category`
(
    `id`           bigint       NOT NULL AUTO_INCREMENT,
    `code`         varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `korean_name`  varchar(100) NOT NULL,
    `translations` json         NOT NULL,
    `sort_order`   int          NOT NULL,
    `status`       enum ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    `created_at`   datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`   datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ingredient_category_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 분류는 선택 속성이다. 포괄 code(SEAFOOD·BROTH)는 한 분류에 넣을 수 없어 NULL 로 둔다.
-- NULL 은 판정 제외 조건이 아니다 — 분류는 탐색 축이고 위험 판정과 무관하다.
ALTER TABLE `ingredients`
    ADD COLUMN `category_id` bigint NULL,
    ADD KEY `idx_ingredients_category` (`category_id`),
    ADD CONSTRAINT `fk_ingredients_category` FOREIGN KEY (`category_id`) REFERENCES `ingredient_category` (`id`) ON DELETE RESTRICT;

-- 음식 JSON(food.ingredients)의 관계 테이블 버전. 이행 기간(이중 쓰기~비교)의 정본은 JSON 이고 이 테이블은 파생이다.
-- 음식 단위로 집합을 통째로 교체하므로 행 id·status·version 이 없다(동시성은 food.version).
-- inclusion_percent 는 포함 확률 1..100 — 0 은 저장 경계에서 생략한다(판정 함수가 0 을 받지 않는다).
-- 역방향 인덱스 (ingredient_id, food_id) 는 "이 재료가 든 음식" 역조회용이다.
-- 재료 쪽은 CASCADE 금지 — 재료 행이 지워져 관계가 조용히 사라지면 회피 판정이 SAFE 로 새어 나간다.
-- 음식 쪽은 CASCADE 다. 관계 행은 음식이 통째로 소유하고, 운영은 소프트 삭제라 음식 행이 실제로 지워지는 건
-- 테스트 정리와 k6 시험 음식 정리뿐이다 — 그 정리 SQL 이 관계 행을 몰라도 막히지 않게 한다.
CREATE TABLE `food_ingredient`
(
    `food_id`           bigint NOT NULL,
    `ingredient_id`     bigint NOT NULL,
    `inclusion_percent` int    NOT NULL,
    `sort_order`        int    NOT NULL,
    PRIMARY KEY (`food_id`, `ingredient_id`),
    KEY `idx_food_ingredient_ingredient_food` (`ingredient_id`, `food_id`),
    CONSTRAINT `fk_food_ingredient_food` FOREIGN KEY (`food_id`) REFERENCES `food` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_food_ingredient_ingredient` FOREIGN KEY (`ingredient_id`) REFERENCES `ingredients` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_food_ingredient_inclusion_percent` CHECK (`inclusion_percent` BETWEEN 1 AND 100)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 관계 테이블은 "재료 미조사(JSON NULL)"와 "재료 없음(JSON [])"을 둘 다 0행으로 만든다.
-- 판정은 READY+NULL 을 UNKNOWN, [] 를 SAFE 로 가르므로 그 구분을 이 플래그가 보존한다. 기존 행은 0 으로 시작하고 백필이 채운다.
ALTER TABLE `food`
    ADD COLUMN `ingredients_assessed` tinyint(1) NOT NULL DEFAULT 0;

INSERT INTO `ingredient_category` (`code`, `korean_name`, `translations`, `sort_order`) VALUES
('MEAT', '육류·동물성 원료', '{"en": "Meat & animal-derived"}', 10),
('EGG', '알류', '{"en": "Eggs"}', 20),
('DAIRY', '우유·유제품', '{"en": "Milk & dairy"}', 30),
('FISH', '어류', '{"en": "Fish"}', 40),
('CRUSTACEAN', '갑각류', '{"en": "Crustaceans"}', 50),
('MOLLUSK', '연체동물', '{"en": "Molluscs"}', 60),
('GRAIN', '곡류·유사곡류', '{"en": "Grains & pseudocereals"}', 70),
('LEGUME', '콩류(대두·땅콩)', '{"en": "Legumes (soy & peanut)"}', 80),
('NUT_SEED', '견과·씨앗', '{"en": "Nuts & seeds"}', 90),
('PRODUCE', '채소·과일·버섯·해조', '{"en": "Vegetables, fruits, mushrooms & seaweed"}', 100),
('ADDITIVE', '복합조미료·주류·첨가물', '{"en": "Seasonings, alcohol & additives"}', 110);

-- 81종 → 11분류 1차 매핑(plan v2 §5-1). SEAFOOD·BROTH 는 NULL 유지.
-- 판단이 애매했던 셋: HONEY→MEAT(동물성 원료, 비건 축) · DASHI→FISH(가쓰오 기반) · MUSTARD→NUT_SEED(씨앗).
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'MEAT')
WHERE `code` IN ('BEEF', 'PORK', 'LARD', 'TALLOW', 'CHICKEN', 'POULTRY', 'GELATIN', 'RENNET', 'CARMINE', 'HONEY');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'EGG')
WHERE `code` IN ('EGG');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'DAIRY')
WHERE `code` IN ('MILK', 'DAIRY', 'GOAT_MILK', 'BUTTER', 'GHEE', 'CHEESE');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'FISH')
WHERE `code` IN ('FISH', 'MACKEREL', 'SALMON', 'TUNA', 'COD', 'ANCHOVY', 'FISH_SAUCE', 'DASHI');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'CRUSTACEAN')
WHERE `code` IN ('SHRIMP', 'SALTED_SHRIMP', 'CRAB', 'CRAYFISH', 'LOBSTER');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'MOLLUSK')
WHERE `code` IN ('SQUID', 'OCTOPUS', 'OYSTER', 'OYSTER_SAUCE', 'ABALONE', 'MUSSEL', 'CLAM', 'SHORT_NECK_CLAM', 'SCALLOP');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'GRAIN')
WHERE `code` IN ('WHEAT', 'BUCKWHEAT', 'BARLEY', 'RYE', 'OAT', 'CORN');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'LEGUME')
WHERE `code` IN ('SOY', 'PEANUT', 'LUPIN', 'PEA', 'CHICKPEA', 'LENTIL');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'NUT_SEED')
WHERE `code` IN ('WALNUT', 'PINE_NUT', 'ALMOND', 'CASHEW', 'PISTACHIO', 'HAZELNUT', 'MACADAMIA', 'PECAN', 'BRAZIL_NUT',
                 'CHESTNUT', 'SESAME', 'SUNFLOWER_SEED', 'MUSTARD');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'PRODUCE')
WHERE `code` IN ('PEACH', 'TOMATO', 'CELERY', 'POTATO', 'CARROT', 'ONION', 'GARLIC', 'SCALLION', 'CHIVE', 'WILD_CHIVE',
                 'ASAFOETIDA');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'ADDITIVE')
WHERE `code` IN ('ALCOHOL', 'MIRIN', 'COOKING_WINE', 'SULFITES');
