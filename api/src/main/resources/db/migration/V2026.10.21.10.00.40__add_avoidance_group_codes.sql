-- KB-842: 회피 묶음 코드 4종(견과류·갑각류·연체류·육류) + 고기 육수·양고기 카탈로그 가산.
-- 함의(묶음 → 하위)는 IngredientCode.IMPLICATIONS 가 판정 시 계산한다 — 여기서는 선택 화면용 카탈로그 행만 넣는다(DDL 없음).
-- 주의: ingredients.code 는 엔티티에서 enum 문자열 매핑이라, 롤링 배포 중 이 행을 구 태스크가 전체 조회하면 변환 예외가 난다(카탈로그 2 API, 수 분).
INSERT INTO ingredients (code, korean_name, translations) VALUES
('NUTS', '견과류', '{"en": "Nuts", "es": "Frutos secos", "id": "Kacang-kacangan", "ja": "ナッツ類", "ru": "Орехи", "th": "ถั่วเปลือกแข็ง", "vi": "Các loại hạt", "zh-Hans": "坚果类", "zh-Hant": "堅果類"}'),
('CRUSTACEAN', '갑각류', '{"en": "Crustaceans", "es": "Crustáceos", "id": "Krustasea", "ja": "甲殻類", "ru": "Ракообразные", "th": "สัตว์จำพวกกุ้งปู", "vi": "Giáp xác", "zh-Hans": "甲壳类", "zh-Hant": "甲殼類"}'),
('MOLLUSK', '연체류', '{"en": "Molluscs", "es": "Moluscos", "id": "Moluska", "ja": "軟体類", "ru": "Моллюски", "th": "สัตว์จำพวกหอยและหมึก", "vi": "Động vật thân mềm", "zh-Hans": "软体类", "zh-Hant": "軟體類"}'),
('MEAT', '육류', '{"en": "Meat", "es": "Carne", "id": "Daging", "ja": "肉類", "ru": "Мясо", "th": "เนื้อสัตว์", "vi": "Thịt", "zh-Hans": "肉类", "zh-Hant": "肉類"}'),
('MEAT_BROTH', '고기 육수', '{"en": "Meat broth", "es": "Caldo de carne", "id": "Kaldu daging", "ja": "肉のだし", "ru": "Мясной бульон", "th": "น้ำซุปเนื้อสัตว์", "vi": "Nước dùng thịt", "zh-Hans": "肉汤", "zh-Hant": "肉湯"}'),
('LAMB', '양고기', '{"en": "Lamb", "es": "Cordero", "id": "Daging domba", "ja": "羊肉", "ru": "Баранина", "th": "เนื้อแกะ", "vi": "Thịt cừu", "zh-Hans": "羊肉", "zh-Hant": "羊肉"}');

UPDATE ingredients
SET image_path = CONCAT('images/webp/ingredients-cut/', LOWER(code), '.webp')
WHERE code IN ('NUTS', 'CRUSTACEAN', 'MOLLUSK', 'MEAT', 'MEAT_BROTH', 'LAMB');

UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'NUT_SEED')
WHERE `code` IN ('NUTS');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'CRUSTACEAN')
WHERE `code` IN ('CRUSTACEAN');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'MOLLUSK')
WHERE `code` IN ('MOLLUSK');
UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'MEAT')
WHERE `code` IN ('MEAT', 'MEAT_BROTH', 'LAMB');
