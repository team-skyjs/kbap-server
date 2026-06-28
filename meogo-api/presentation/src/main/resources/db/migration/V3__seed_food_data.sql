-- V3__seed_food_data.sql
-- Food demo seed: 10 representative Korean menus.
-- Korean name lives only in food.korean_name / ingredient.korean_name.
-- Each food and each ingredient carries all 9 target-language translations
-- (zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es) — Korean (ko) is NOT in the translation tables.
-- Ingredients are a single deduplicated, heavily-reused pool.
-- All rows: status='ACTIVE', created_at/updated_at = NOW(6). image_ref/icon_ref = NULL (no assets yet).

-- ============================================================
-- 1) INGREDIENTS (shared, deduplicated pool — ids 1..30)
-- ============================================================
INSERT INTO ingredient (id, korean_name, icon_ref, status, created_at, updated_at) VALUES
(1,  '두부',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- tofu
(2,  '돼지고기', NULL, 'ACTIVE', NOW(6), NOW(6)),  -- pork
(3,  '소고기',   NULL, 'ACTIVE', NOW(6), NOW(6)),  -- beef
(4,  '김치',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- kimchi
(5,  '대파',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- green onion
(6,  '마늘',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- garlic
(7,  '양파',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- onion
(8,  '된장',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- soybean paste
(9,  '고추장',   NULL, 'ACTIVE', NOW(6), NOW(6)),  -- red chili paste
(10, '고춧가루', NULL, 'ACTIVE', NOW(6), NOW(6)),  -- chili powder
(11, '애호박',   NULL, 'ACTIVE', NOW(6), NOW(6)),  -- zucchini
(12, '감자',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- potato
(13, '계란',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- egg
(14, '쌀밥',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- cooked rice
(15, '당근',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- carrot
(16, '시금치',   NULL, 'ACTIVE', NOW(6), NOW(6)),  -- spinach
(17, '콩나물',   NULL, 'ACTIVE', NOW(6), NOW(6)),  -- soybean sprouts
(18, '참기름',   NULL, 'ACTIVE', NOW(6), NOW(6)),  -- sesame oil
(19, '간장',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- soy sauce
(20, '설탕',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- sugar
(21, '떡',       NULL, 'ACTIVE', NOW(6), NOW(6)),  -- rice cake
(22, '어묵',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- fish cake
(23, '김',       NULL, 'ACTIVE', NOW(6), NOW(6)),  -- dried seaweed (gim)
(24, '단무지',   NULL, 'ACTIVE', NOW(6), NOW(6)),  -- pickled radish
(25, '햄',       NULL, 'ACTIVE', NOW(6), NOW(6)),  -- ham
(26, '당면',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- glass noodles
(27, '버섯',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- mushroom
(28, '오이',     NULL, 'ACTIVE', NOW(6), NOW(6)),  -- cucumber
(29, '메밀면',   NULL, 'ACTIVE', NOW(6), NOW(6)),  -- buckwheat noodles
(30, '육수',     NULL, 'ACTIVE', NOW(6), NOW(6));  -- broth

-- ============================================================
-- 2) INGREDIENT NAME TRANSLATIONS (9 languages per ingredient)
-- ============================================================

-- 1 두부 / tofu
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(1,   1, 'zh-Hans', '豆腐',          'ACTIVE', NOW(6), NOW(6)),
(2,   1, 'en',      'Tofu',          'ACTIVE', NOW(6), NOW(6)),
(3,   1, 'ja',      '豆腐',          'ACTIVE', NOW(6), NOW(6)),
(4,   1, 'zh-Hant', '豆腐',          'ACTIVE', NOW(6), NOW(6)),
(5,   1, 'vi',      'Đậu phụ',       'ACTIVE', NOW(6), NOW(6)),
(6,   1, 'id',      'Tahu',          'ACTIVE', NOW(6), NOW(6)),
(7,   1, 'th',      'เต้าหู้',          'ACTIVE', NOW(6), NOW(6)),
(8,   1, 'ru',      'Тофу',          'ACTIVE', NOW(6), NOW(6)),
(9,   1, 'es',      'Tofu',          'ACTIVE', NOW(6), NOW(6));

-- 2 돼지고기 / pork
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(10,  2, 'zh-Hans', '猪肉',          'ACTIVE', NOW(6), NOW(6)),
(11,  2, 'en',      'Pork',          'ACTIVE', NOW(6), NOW(6)),
(12,  2, 'ja',      '豚肉',          'ACTIVE', NOW(6), NOW(6)),
(13,  2, 'zh-Hant', '豬肉',          'ACTIVE', NOW(6), NOW(6)),
(14,  2, 'vi',      'Thịt heo',      'ACTIVE', NOW(6), NOW(6)),
(15,  2, 'id',      'Daging babi',   'ACTIVE', NOW(6), NOW(6)),
(16,  2, 'th',      'เนื้อหมู',         'ACTIVE', NOW(6), NOW(6)),
(17,  2, 'ru',      'Свинина',       'ACTIVE', NOW(6), NOW(6)),
(18,  2, 'es',      'Cerdo',         'ACTIVE', NOW(6), NOW(6));

-- 3 소고기 / beef
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(19,  3, 'zh-Hans', '牛肉',          'ACTIVE', NOW(6), NOW(6)),
(20,  3, 'en',      'Beef',          'ACTIVE', NOW(6), NOW(6)),
(21,  3, 'ja',      '牛肉',          'ACTIVE', NOW(6), NOW(6)),
(22,  3, 'zh-Hant', '牛肉',          'ACTIVE', NOW(6), NOW(6)),
(23,  3, 'vi',      'Thịt bò',       'ACTIVE', NOW(6), NOW(6)),
(24,  3, 'id',      'Daging sapi',   'ACTIVE', NOW(6), NOW(6)),
(25,  3, 'th',      'เนื้อวัว',         'ACTIVE', NOW(6), NOW(6)),
(26,  3, 'ru',      'Говядина',      'ACTIVE', NOW(6), NOW(6)),
(27,  3, 'es',      'Carne de res',  'ACTIVE', NOW(6), NOW(6));

-- 4 김치 / kimchi
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(28,  4, 'zh-Hans', '泡菜',          'ACTIVE', NOW(6), NOW(6)),
(29,  4, 'en',      'Kimchi',        'ACTIVE', NOW(6), NOW(6)),
(30,  4, 'ja',      'キムチ',        'ACTIVE', NOW(6), NOW(6)),
(31,  4, 'zh-Hant', '泡菜',          'ACTIVE', NOW(6), NOW(6)),
(32,  4, 'vi',      'Kimchi',        'ACTIVE', NOW(6), NOW(6)),
(33,  4, 'id',      'Kimchi',        'ACTIVE', NOW(6), NOW(6)),
(34,  4, 'th',      'กิมจิ',           'ACTIVE', NOW(6), NOW(6)),
(35,  4, 'ru',      'Кимчи',         'ACTIVE', NOW(6), NOW(6)),
(36,  4, 'es',      'Kimchi',        'ACTIVE', NOW(6), NOW(6));

-- 5 대파 / green onion
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(37,  5, 'zh-Hans', '大葱',          'ACTIVE', NOW(6), NOW(6)),
(38,  5, 'en',      'Green onion',   'ACTIVE', NOW(6), NOW(6)),
(39,  5, 'ja',      '長ねぎ',        'ACTIVE', NOW(6), NOW(6)),
(40,  5, 'zh-Hant', '大蔥',          'ACTIVE', NOW(6), NOW(6)),
(41,  5, 'vi',      'Hành lá',       'ACTIVE', NOW(6), NOW(6)),
(42,  5, 'id',      'Daun bawang',   'ACTIVE', NOW(6), NOW(6)),
(43,  5, 'th',      'ต้นหอม',         'ACTIVE', NOW(6), NOW(6)),
(44,  5, 'ru',      'Зелёный лук',   'ACTIVE', NOW(6), NOW(6)),
(45,  5, 'es',      'Cebolleta',     'ACTIVE', NOW(6), NOW(6));

-- 6 마늘 / garlic
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(46,  6, 'zh-Hans', '大蒜',          'ACTIVE', NOW(6), NOW(6)),
(47,  6, 'en',      'Garlic',        'ACTIVE', NOW(6), NOW(6)),
(48,  6, 'ja',      'にんにく',      'ACTIVE', NOW(6), NOW(6)),
(49,  6, 'zh-Hant', '大蒜',          'ACTIVE', NOW(6), NOW(6)),
(50,  6, 'vi',      'Tỏi',           'ACTIVE', NOW(6), NOW(6)),
(51,  6, 'id',      'Bawang putih',  'ACTIVE', NOW(6), NOW(6)),
(52,  6, 'th',      'กระเทียม',        'ACTIVE', NOW(6), NOW(6)),
(53,  6, 'ru',      'Чеснок',        'ACTIVE', NOW(6), NOW(6)),
(54,  6, 'es',      'Ajo',           'ACTIVE', NOW(6), NOW(6));

-- 7 양파 / onion
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(55,  7, 'zh-Hans', '洋葱',          'ACTIVE', NOW(6), NOW(6)),
(56,  7, 'en',      'Onion',         'ACTIVE', NOW(6), NOW(6)),
(57,  7, 'ja',      '玉ねぎ',        'ACTIVE', NOW(6), NOW(6)),
(58,  7, 'zh-Hant', '洋蔥',          'ACTIVE', NOW(6), NOW(6)),
(59,  7, 'vi',      'Hành tây',      'ACTIVE', NOW(6), NOW(6)),
(60,  7, 'id',      'Bawang bombai', 'ACTIVE', NOW(6), NOW(6)),
(61,  7, 'th',      'หัวหอมใหญ่',      'ACTIVE', NOW(6), NOW(6)),
(62,  7, 'ru',      'Лук',           'ACTIVE', NOW(6), NOW(6)),
(63,  7, 'es',      'Cebolla',       'ACTIVE', NOW(6), NOW(6));

-- 8 된장 / soybean paste
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(64,  8, 'zh-Hans', '大酱',              'ACTIVE', NOW(6), NOW(6)),
(65,  8, 'en',      'Soybean paste',     'ACTIVE', NOW(6), NOW(6)),
(66,  8, 'ja',      'テンジャン',        'ACTIVE', NOW(6), NOW(6)),
(67,  8, 'zh-Hant', '大醬',              'ACTIVE', NOW(6), NOW(6)),
(68,  8, 'vi',      'Tương đậu',         'ACTIVE', NOW(6), NOW(6)),
(69,  8, 'id',      'Pasta kedelai',     'ACTIVE', NOW(6), NOW(6)),
(70,  8, 'th',      'เต้าเจี้ยว',           'ACTIVE', NOW(6), NOW(6)),
(71,  8, 'ru',      'Соевая паста',      'ACTIVE', NOW(6), NOW(6)),
(72,  8, 'es',      'Pasta de soja',     'ACTIVE', NOW(6), NOW(6));

-- 9 고추장 / red chili paste
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(73,  9, 'zh-Hans', '辣椒酱',                'ACTIVE', NOW(6), NOW(6)),
(74,  9, 'en',      'Red chili paste',       'ACTIVE', NOW(6), NOW(6)),
(75,  9, 'ja',      'コチュジャン',          'ACTIVE', NOW(6), NOW(6)),
(76,  9, 'zh-Hant', '辣椒醬',                'ACTIVE', NOW(6), NOW(6)),
(77,  9, 'vi',      'Tương ớt',              'ACTIVE', NOW(6), NOW(6)),
(78,  9, 'id',      'Pasta cabai',           'ACTIVE', NOW(6), NOW(6)),
(79,  9, 'th',      'โกชูจัง',                 'ACTIVE', NOW(6), NOW(6)),
(80,  9, 'ru',      'Острая паста кочхуджан','ACTIVE', NOW(6), NOW(6)),
(81,  9, 'es',      'Pasta de chile',        'ACTIVE', NOW(6), NOW(6));

-- 10 고춧가루 / chili powder
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(82,  10, 'zh-Hans', '辣椒粉',         'ACTIVE', NOW(6), NOW(6)),
(83,  10, 'en',      'Chili powder',   'ACTIVE', NOW(6), NOW(6)),
(84,  10, 'ja',      '粉唐辛子',       'ACTIVE', NOW(6), NOW(6)),
(85,  10, 'zh-Hant', '辣椒粉',         'ACTIVE', NOW(6), NOW(6)),
(86,  10, 'vi',      'Bột ớt',         'ACTIVE', NOW(6), NOW(6)),
(87,  10, 'id',      'Bubuk cabai',    'ACTIVE', NOW(6), NOW(6)),
(88,  10, 'th',      'พริกป่น',          'ACTIVE', NOW(6), NOW(6)),
(89,  10, 'ru',      'Перец чили молотый','ACTIVE', NOW(6), NOW(6)),
(90,  10, 'es',      'Chile en polvo', 'ACTIVE', NOW(6), NOW(6));

-- 11 애호박 / zucchini
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(91,  11, 'zh-Hans', '西葫芦',      'ACTIVE', NOW(6), NOW(6)),
(92,  11, 'en',      'Zucchini',    'ACTIVE', NOW(6), NOW(6)),
(93,  11, 'ja',      'ズッキーニ',  'ACTIVE', NOW(6), NOW(6)),
(94,  11, 'zh-Hant', '櫛瓜',        'ACTIVE', NOW(6), NOW(6)),
(95,  11, 'vi',      'Bí ngòi',     'ACTIVE', NOW(6), NOW(6)),
(96,  11, 'id',      'Zukini',      'ACTIVE', NOW(6), NOW(6)),
(97,  11, 'th',      'บวบ',          'ACTIVE', NOW(6), NOW(6)),
(98,  11, 'ru',      'Кабачок',     'ACTIVE', NOW(6), NOW(6)),
(99,  11, 'es',      'Calabacín',   'ACTIVE', NOW(6), NOW(6));

-- 12 감자 / potato
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(100, 12, 'zh-Hans', '土豆',     'ACTIVE', NOW(6), NOW(6)),
(101, 12, 'en',      'Potato',   'ACTIVE', NOW(6), NOW(6)),
(102, 12, 'ja',      'じゃがいも','ACTIVE', NOW(6), NOW(6)),
(103, 12, 'zh-Hant', '馬鈴薯',   'ACTIVE', NOW(6), NOW(6)),
(104, 12, 'vi',      'Khoai tây','ACTIVE', NOW(6), NOW(6)),
(105, 12, 'id',      'Kentang',  'ACTIVE', NOW(6), NOW(6)),
(106, 12, 'th',      'มันฝรั่ง',    'ACTIVE', NOW(6), NOW(6)),
(107, 12, 'ru',      'Картофель','ACTIVE', NOW(6), NOW(6)),
(108, 12, 'es',      'Patata',   'ACTIVE', NOW(6), NOW(6));

-- 13 계란 / egg
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(109, 13, 'zh-Hans', '鸡蛋',   'ACTIVE', NOW(6), NOW(6)),
(110, 13, 'en',      'Egg',    'ACTIVE', NOW(6), NOW(6)),
(111, 13, 'ja',      '卵',     'ACTIVE', NOW(6), NOW(6)),
(112, 13, 'zh-Hant', '雞蛋',   'ACTIVE', NOW(6), NOW(6)),
(113, 13, 'vi',      'Trứng',  'ACTIVE', NOW(6), NOW(6)),
(114, 13, 'id',      'Telur',  'ACTIVE', NOW(6), NOW(6)),
(115, 13, 'th',      'ไข่',      'ACTIVE', NOW(6), NOW(6)),
(116, 13, 'ru',      'Яйцо',   'ACTIVE', NOW(6), NOW(6)),
(117, 13, 'es',      'Huevo',  'ACTIVE', NOW(6), NOW(6));

-- 14 쌀밥 / cooked rice
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(118, 14, 'zh-Hans', '米饭',         'ACTIVE', NOW(6), NOW(6)),
(119, 14, 'en',      'Cooked rice',  'ACTIVE', NOW(6), NOW(6)),
(120, 14, 'ja',      'ご飯',         'ACTIVE', NOW(6), NOW(6)),
(121, 14, 'zh-Hant', '米飯',         'ACTIVE', NOW(6), NOW(6)),
(122, 14, 'vi',      'Cơm',          'ACTIVE', NOW(6), NOW(6)),
(123, 14, 'id',      'Nasi',         'ACTIVE', NOW(6), NOW(6)),
(124, 14, 'th',      'ข้าวสวย',       'ACTIVE', NOW(6), NOW(6)),
(125, 14, 'ru',      'Рис',          'ACTIVE', NOW(6), NOW(6)),
(126, 14, 'es',      'Arroz cocido', 'ACTIVE', NOW(6), NOW(6));

-- 15 당근 / carrot
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(127, 15, 'zh-Hans', '胡萝卜',   'ACTIVE', NOW(6), NOW(6)),
(128, 15, 'en',      'Carrot',   'ACTIVE', NOW(6), NOW(6)),
(129, 15, 'ja',      'にんじん', 'ACTIVE', NOW(6), NOW(6)),
(130, 15, 'zh-Hant', '胡蘿蔔',   'ACTIVE', NOW(6), NOW(6)),
(131, 15, 'vi',      'Cà rốt',   'ACTIVE', NOW(6), NOW(6)),
(132, 15, 'id',      'Wortel',   'ACTIVE', NOW(6), NOW(6)),
(133, 15, 'th',      'แครอท',     'ACTIVE', NOW(6), NOW(6)),
(134, 15, 'ru',      'Морковь',  'ACTIVE', NOW(6), NOW(6)),
(135, 15, 'es',      'Zanahoria','ACTIVE', NOW(6), NOW(6));

-- 16 시금치 / spinach
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(136, 16, 'zh-Hans', '菠菜',     'ACTIVE', NOW(6), NOW(6)),
(137, 16, 'en',      'Spinach',  'ACTIVE', NOW(6), NOW(6)),
(138, 16, 'ja',      'ほうれん草','ACTIVE', NOW(6), NOW(6)),
(139, 16, 'zh-Hant', '菠菜',     'ACTIVE', NOW(6), NOW(6)),
(140, 16, 'vi',      'Rau chân vịt','ACTIVE', NOW(6), NOW(6)),
(141, 16, 'id',      'Bayam',    'ACTIVE', NOW(6), NOW(6)),
(142, 16, 'th',      'ผักโขม',     'ACTIVE', NOW(6), NOW(6)),
(143, 16, 'ru',      'Шпинат',   'ACTIVE', NOW(6), NOW(6)),
(144, 16, 'es',      'Espinaca', 'ACTIVE', NOW(6), NOW(6));

-- 17 콩나물 / soybean sprouts
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(145, 17, 'zh-Hans', '黄豆芽',          'ACTIVE', NOW(6), NOW(6)),
(146, 17, 'en',      'Soybean sprouts', 'ACTIVE', NOW(6), NOW(6)),
(147, 17, 'ja',      '豆もやし',        'ACTIVE', NOW(6), NOW(6)),
(148, 17, 'zh-Hant', '黃豆芽',          'ACTIVE', NOW(6), NOW(6)),
(149, 17, 'vi',      'Giá đỗ tương',    'ACTIVE', NOW(6), NOW(6)),
(150, 17, 'id',      'Tauge kedelai',   'ACTIVE', NOW(6), NOW(6)),
(151, 17, 'th',      'ถั่วงอกหัวโต',       'ACTIVE', NOW(6), NOW(6)),
(152, 17, 'ru',      'Соевые ростки',   'ACTIVE', NOW(6), NOW(6)),
(153, 17, 'es',      'Brotes de soja',  'ACTIVE', NOW(6), NOW(6));

-- 18 참기름 / sesame oil
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(154, 18, 'zh-Hans', '香油',          'ACTIVE', NOW(6), NOW(6)),
(155, 18, 'en',      'Sesame oil',    'ACTIVE', NOW(6), NOW(6)),
(156, 18, 'ja',      'ごま油',        'ACTIVE', NOW(6), NOW(6)),
(157, 18, 'zh-Hant', '香油',          'ACTIVE', NOW(6), NOW(6)),
(158, 18, 'vi',      'Dầu mè',        'ACTIVE', NOW(6), NOW(6)),
(159, 18, 'id',      'Minyak wijen',  'ACTIVE', NOW(6), NOW(6)),
(160, 18, 'th',      'น้ำมันงา',        'ACTIVE', NOW(6), NOW(6)),
(161, 18, 'ru',      'Кунжутное масло','ACTIVE', NOW(6), NOW(6)),
(162, 18, 'es',      'Aceite de sésamo','ACTIVE', NOW(6), NOW(6));

-- 19 간장 / soy sauce
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(163, 19, 'zh-Hans', '酱油',        'ACTIVE', NOW(6), NOW(6)),
(164, 19, 'en',      'Soy sauce',   'ACTIVE', NOW(6), NOW(6)),
(165, 19, 'ja',      '醤油',        'ACTIVE', NOW(6), NOW(6)),
(166, 19, 'zh-Hant', '醬油',        'ACTIVE', NOW(6), NOW(6)),
(167, 19, 'vi',      'Nước tương',  'ACTIVE', NOW(6), NOW(6)),
(168, 19, 'id',      'Kecap asin',  'ACTIVE', NOW(6), NOW(6)),
(169, 19, 'th',      'ซีอิ๊ว',         'ACTIVE', NOW(6), NOW(6)),
(170, 19, 'ru',      'Соевый соус', 'ACTIVE', NOW(6), NOW(6)),
(171, 19, 'es',      'Salsa de soja','ACTIVE', NOW(6), NOW(6));

-- 20 설탕 / sugar
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(172, 20, 'zh-Hans', '糖',      'ACTIVE', NOW(6), NOW(6)),
(173, 20, 'en',      'Sugar',   'ACTIVE', NOW(6), NOW(6)),
(174, 20, 'ja',      '砂糖',    'ACTIVE', NOW(6), NOW(6)),
(175, 20, 'zh-Hant', '糖',      'ACTIVE', NOW(6), NOW(6)),
(176, 20, 'vi',      'Đường',   'ACTIVE', NOW(6), NOW(6)),
(177, 20, 'id',      'Gula',    'ACTIVE', NOW(6), NOW(6)),
(178, 20, 'th',      'น้ำตาล',    'ACTIVE', NOW(6), NOW(6)),
(179, 20, 'ru',      'Сахар',   'ACTIVE', NOW(6), NOW(6)),
(180, 20, 'es',      'Azúcar',  'ACTIVE', NOW(6), NOW(6));

-- 21 떡 / rice cake
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(181, 21, 'zh-Hans', '年糕',         'ACTIVE', NOW(6), NOW(6)),
(182, 21, 'en',      'Rice cake',    'ACTIVE', NOW(6), NOW(6)),
(183, 21, 'ja',      'トック',       'ACTIVE', NOW(6), NOW(6)),
(184, 21, 'zh-Hant', '年糕',         'ACTIVE', NOW(6), NOW(6)),
(185, 21, 'vi',      'Bánh gạo',     'ACTIVE', NOW(6), NOW(6)),
(186, 21, 'id',      'Kue beras',    'ACTIVE', NOW(6), NOW(6)),
(187, 21, 'th',      'ต๊อก',          'ACTIVE', NOW(6), NOW(6)),
(188, 21, 'ru',      'Рисовые палочки токпокки','ACTIVE', NOW(6), NOW(6)),
(189, 21, 'es',      'Pastel de arroz','ACTIVE', NOW(6), NOW(6));

-- 22 어묵 / fish cake
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(190, 22, 'zh-Hans', '鱼饼',     'ACTIVE', NOW(6), NOW(6)),
(191, 22, 'en',      'Fish cake','ACTIVE', NOW(6), NOW(6)),
(192, 22, 'ja',      'おでん',   'ACTIVE', NOW(6), NOW(6)),
(193, 22, 'zh-Hant', '魚餅',     'ACTIVE', NOW(6), NOW(6)),
(194, 22, 'vi',      'Chả cá',   'ACTIVE', NOW(6), NOW(6)),
(195, 22, 'id',      'Otak-otak','ACTIVE', NOW(6), NOW(6)),
(196, 22, 'th',      'ลูกชิ้นปลา', 'ACTIVE', NOW(6), NOW(6)),
(197, 22, 'ru',      'Рыбные котлетки','ACTIVE', NOW(6), NOW(6)),
(198, 22, 'es',      'Pastel de pescado','ACTIVE', NOW(6), NOW(6));

-- 23 김 / dried seaweed (gim)
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(199, 23, 'zh-Hans', '海苔',         'ACTIVE', NOW(6), NOW(6)),
(200, 23, 'en',      'Dried seaweed','ACTIVE', NOW(6), NOW(6)),
(201, 23, 'ja',      '海苔',         'ACTIVE', NOW(6), NOW(6)),
(202, 23, 'zh-Hant', '海苔',         'ACTIVE', NOW(6), NOW(6)),
(203, 23, 'vi',      'Rong biển khô','ACTIVE', NOW(6), NOW(6)),
(204, 23, 'id',      'Rumput laut',  'ACTIVE', NOW(6), NOW(6)),
(205, 23, 'th',      'สาหร่าย',       'ACTIVE', NOW(6), NOW(6)),
(206, 23, 'ru',      'Сушёные водоросли','ACTIVE', NOW(6), NOW(6)),
(207, 23, 'es',      'Alga seca',    'ACTIVE', NOW(6), NOW(6));

-- 24 단무지 / pickled radish
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(208, 24, 'zh-Hans', '腌萝卜',           'ACTIVE', NOW(6), NOW(6)),
(209, 24, 'en',      'Pickled radish',   'ACTIVE', NOW(6), NOW(6)),
(210, 24, 'ja',      'たくあん',         'ACTIVE', NOW(6), NOW(6)),
(211, 24, 'zh-Hant', '醃蘿蔔',           'ACTIVE', NOW(6), NOW(6)),
(212, 24, 'vi',      'Củ cải muối',      'ACTIVE', NOW(6), NOW(6)),
(213, 24, 'id',      'Acar lobak',       'ACTIVE', NOW(6), NOW(6)),
(214, 24, 'th',      'หัวไชเท้าดอง',       'ACTIVE', NOW(6), NOW(6)),
(215, 24, 'ru',      'Маринованная редька','ACTIVE', NOW(6), NOW(6)),
(216, 24, 'es',      'Rábano encurtido', 'ACTIVE', NOW(6), NOW(6));

-- 25 햄 / ham
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(217, 25, 'zh-Hans', '火腿',  'ACTIVE', NOW(6), NOW(6)),
(218, 25, 'en',      'Ham',   'ACTIVE', NOW(6), NOW(6)),
(219, 25, 'ja',      'ハム',  'ACTIVE', NOW(6), NOW(6)),
(220, 25, 'zh-Hant', '火腿',  'ACTIVE', NOW(6), NOW(6)),
(221, 25, 'vi',      'Giăm bông','ACTIVE', NOW(6), NOW(6)),
(222, 25, 'id',      'Ham',   'ACTIVE', NOW(6), NOW(6)),
(223, 25, 'th',      'แฮม',    'ACTIVE', NOW(6), NOW(6)),
(224, 25, 'ru',      'Ветчина','ACTIVE', NOW(6), NOW(6)),
(225, 25, 'es',      'Jamón', 'ACTIVE', NOW(6), NOW(6));

-- 26 당면 / glass noodles
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(226, 26, 'zh-Hans', '粉条',          'ACTIVE', NOW(6), NOW(6)),
(227, 26, 'en',      'Glass noodles', 'ACTIVE', NOW(6), NOW(6)),
(228, 26, 'ja',      '春雨',          'ACTIVE', NOW(6), NOW(6)),
(229, 26, 'zh-Hant', '冬粉',          'ACTIVE', NOW(6), NOW(6)),
(230, 26, 'vi',      'Miến',          'ACTIVE', NOW(6), NOW(6)),
(231, 26, 'id',      'Soun',          'ACTIVE', NOW(6), NOW(6)),
(232, 26, 'th',      'วุ้นเส้น',         'ACTIVE', NOW(6), NOW(6)),
(233, 26, 'ru',      'Стеклянная лапша','ACTIVE', NOW(6), NOW(6)),
(234, 26, 'es',      'Fideos de cristal','ACTIVE', NOW(6), NOW(6));

-- 27 버섯 / mushroom
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(235, 27, 'zh-Hans', '蘑菇',     'ACTIVE', NOW(6), NOW(6)),
(236, 27, 'en',      'Mushroom', 'ACTIVE', NOW(6), NOW(6)),
(237, 27, 'ja',      'きのこ',   'ACTIVE', NOW(6), NOW(6)),
(238, 27, 'zh-Hant', '蘑菇',     'ACTIVE', NOW(6), NOW(6)),
(239, 27, 'vi',      'Nấm',      'ACTIVE', NOW(6), NOW(6)),
(240, 27, 'id',      'Jamur',    'ACTIVE', NOW(6), NOW(6)),
(241, 27, 'th',      'เห็ด',       'ACTIVE', NOW(6), NOW(6)),
(242, 27, 'ru',      'Грибы',    'ACTIVE', NOW(6), NOW(6)),
(243, 27, 'es',      'Champiñón','ACTIVE', NOW(6), NOW(6));

-- 28 오이 / cucumber
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(244, 28, 'zh-Hans', '黄瓜',     'ACTIVE', NOW(6), NOW(6)),
(245, 28, 'en',      'Cucumber', 'ACTIVE', NOW(6), NOW(6)),
(246, 28, 'ja',      'きゅうり', 'ACTIVE', NOW(6), NOW(6)),
(247, 28, 'zh-Hant', '黃瓜',     'ACTIVE', NOW(6), NOW(6)),
(248, 28, 'vi',      'Dưa chuột','ACTIVE', NOW(6), NOW(6)),
(249, 28, 'id',      'Mentimun', 'ACTIVE', NOW(6), NOW(6)),
(250, 28, 'th',      'แตงกวา',    'ACTIVE', NOW(6), NOW(6)),
(251, 28, 'ru',      'Огурец',   'ACTIVE', NOW(6), NOW(6)),
(252, 28, 'es',      'Pepino',   'ACTIVE', NOW(6), NOW(6));

-- 29 메밀면 / buckwheat noodles
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(253, 29, 'zh-Hans', '荞麦面',            'ACTIVE', NOW(6), NOW(6)),
(254, 29, 'en',      'Buckwheat noodles', 'ACTIVE', NOW(6), NOW(6)),
(255, 29, 'ja',      'そば',              'ACTIVE', NOW(6), NOW(6)),
(256, 29, 'zh-Hant', '蕎麥麵',            'ACTIVE', NOW(6), NOW(6)),
(257, 29, 'vi',      'Mì kiều mạch',      'ACTIVE', NOW(6), NOW(6)),
(258, 29, 'id',      'Mi soba',           'ACTIVE', NOW(6), NOW(6)),
(259, 29, 'th',      'เส้นบักวีต',          'ACTIVE', NOW(6), NOW(6)),
(260, 29, 'ru',      'Гречневая лапша',   'ACTIVE', NOW(6), NOW(6)),
(261, 29, 'es',      'Fideos de alforfón','ACTIVE', NOW(6), NOW(6));

-- 30 육수 / broth
INSERT INTO ingredient_name_translation (id, ingredient_id, lang_code, name, status, created_at, updated_at) VALUES
(262, 30, 'zh-Hans', '高汤',   'ACTIVE', NOW(6), NOW(6)),
(263, 30, 'en',      'Broth',  'ACTIVE', NOW(6), NOW(6)),
(264, 30, 'ja',      'だし',   'ACTIVE', NOW(6), NOW(6)),
(265, 30, 'zh-Hant', '高湯',   'ACTIVE', NOW(6), NOW(6)),
(266, 30, 'vi',      'Nước dùng','ACTIVE', NOW(6), NOW(6)),
(267, 30, 'id',      'Kaldu',  'ACTIVE', NOW(6), NOW(6)),
(268, 30, 'th',      'น้ำซุป',    'ACTIVE', NOW(6), NOW(6)),
(269, 30, 'ru',      'Бульон', 'ACTIVE', NOW(6), NOW(6)),
(270, 30, 'es',      'Caldo',  'ACTIVE', NOW(6), NOW(6));

-- ============================================================
-- 3) FOODS (10 representative menus — ids 1..10)
-- ============================================================
INSERT INTO food (id, korean_name, image_ref, status, created_at, updated_at) VALUES
(1,  '된장찌개',   NULL, 'ACTIVE', NOW(6), NOW(6)),
(2,  '김치찌개',   NULL, 'ACTIVE', NOW(6), NOW(6)),
(3,  '비빔밥',     NULL, 'ACTIVE', NOW(6), NOW(6)),
(4,  '불고기',     NULL, 'ACTIVE', NOW(6), NOW(6)),
(5,  '삼겹살',     NULL, 'ACTIVE', NOW(6), NOW(6)),
(6,  '떡볶이',     NULL, 'ACTIVE', NOW(6), NOW(6)),
(7,  '김밥',       NULL, 'ACTIVE', NOW(6), NOW(6)),
(8,  '잡채',       NULL, 'ACTIVE', NOW(6), NOW(6)),
(9,  '순두부찌개', NULL, 'ACTIVE', NOW(6), NOW(6)),
(10, '물냉면',     NULL, 'ACTIVE', NOW(6), NOW(6));

-- ============================================================
-- 4) FOOD NAME TRANSLATIONS (9 languages per food)
-- ============================================================

-- 1 된장찌개 / Doenjang Stew
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(1,  1, 'zh-Hans', '大酱汤',             'ACTIVE', NOW(6), NOW(6)),
(2,  1, 'en',      'Doenjang Stew',      'ACTIVE', NOW(6), NOW(6)),
(3,  1, 'ja',      'テンジャンチゲ',     'ACTIVE', NOW(6), NOW(6)),
(4,  1, 'zh-Hant', '大醬湯',             'ACTIVE', NOW(6), NOW(6)),
(5,  1, 'vi',      'Canh tương đậu',     'ACTIVE', NOW(6), NOW(6)),
(6,  1, 'id',      'Sup Pasta Kedelai',  'ACTIVE', NOW(6), NOW(6)),
(7,  1, 'th',      'ซุปเต้าเจี้ยว',          'ACTIVE', NOW(6), NOW(6)),
(8,  1, 'ru',      'Тведжан чигэ',       'ACTIVE', NOW(6), NOW(6)),
(9,  1, 'es',      'Guiso de Doenjang',  'ACTIVE', NOW(6), NOW(6));

-- 2 김치찌개 / Kimchi Stew
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(10, 2, 'zh-Hans', '泡菜汤',           'ACTIVE', NOW(6), NOW(6)),
(11, 2, 'en',      'Kimchi Stew',      'ACTIVE', NOW(6), NOW(6)),
(12, 2, 'ja',      'キムチチゲ',       'ACTIVE', NOW(6), NOW(6)),
(13, 2, 'zh-Hant', '泡菜湯',           'ACTIVE', NOW(6), NOW(6)),
(14, 2, 'vi',      'Canh kimchi',      'ACTIVE', NOW(6), NOW(6)),
(15, 2, 'id',      'Sup Kimchi',       'ACTIVE', NOW(6), NOW(6)),
(16, 2, 'th',      'ซุปกิมจิ',           'ACTIVE', NOW(6), NOW(6)),
(17, 2, 'ru',      'Кимчи чигэ',       'ACTIVE', NOW(6), NOW(6)),
(18, 2, 'es',      'Guiso de Kimchi',  'ACTIVE', NOW(6), NOW(6));

-- 3 비빔밥 / Bibimbap
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(19, 3, 'zh-Hans', '拌饭',     'ACTIVE', NOW(6), NOW(6)),
(20, 3, 'en',      'Bibimbap', 'ACTIVE', NOW(6), NOW(6)),
(21, 3, 'ja',      'ビビンバ', 'ACTIVE', NOW(6), NOW(6)),
(22, 3, 'zh-Hant', '拌飯',     'ACTIVE', NOW(6), NOW(6)),
(23, 3, 'vi',      'Cơm trộn', 'ACTIVE', NOW(6), NOW(6)),
(24, 3, 'id',      'Bibimbap', 'ACTIVE', NOW(6), NOW(6)),
(25, 3, 'th',      'บิบิมบับ',   'ACTIVE', NOW(6), NOW(6)),
(26, 3, 'ru',      'Пибимпап', 'ACTIVE', NOW(6), NOW(6)),
(27, 3, 'es',      'Bibimbap', 'ACTIVE', NOW(6), NOW(6));

-- 4 불고기 / Bulgogi
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(28, 4, 'zh-Hans', '烤牛肉',          'ACTIVE', NOW(6), NOW(6)),
(29, 4, 'en',      'Bulgogi',         'ACTIVE', NOW(6), NOW(6)),
(30, 4, 'ja',      'プルコギ',        'ACTIVE', NOW(6), NOW(6)),
(31, 4, 'zh-Hant', '烤牛肉',          'ACTIVE', NOW(6), NOW(6)),
(32, 4, 'vi',      'Thịt bò nướng',   'ACTIVE', NOW(6), NOW(6)),
(33, 4, 'id',      'Bulgogi',         'ACTIVE', NOW(6), NOW(6)),
(34, 4, 'th',      'พุลโกกิ',           'ACTIVE', NOW(6), NOW(6)),
(35, 4, 'ru',      'Пулькоги',        'ACTIVE', NOW(6), NOW(6)),
(36, 4, 'es',      'Bulgogi',         'ACTIVE', NOW(6), NOW(6));

-- 5 삼겹살 / Grilled Pork Belly
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(37, 5, 'zh-Hans', '烤五花肉',            'ACTIVE', NOW(6), NOW(6)),
(38, 5, 'en',      'Grilled Pork Belly',  'ACTIVE', NOW(6), NOW(6)),
(39, 5, 'ja',      'サムギョプサル',      'ACTIVE', NOW(6), NOW(6)),
(40, 5, 'zh-Hant', '烤五花肉',            'ACTIVE', NOW(6), NOW(6)),
(41, 5, 'vi',      'Ba chỉ nướng',        'ACTIVE', NOW(6), NOW(6)),
(42, 5, 'id',      'Samgyeopsal',         'ACTIVE', NOW(6), NOW(6)),
(43, 5, 'th',      'หมูสามชั้นย่าง',         'ACTIVE', NOW(6), NOW(6)),
(44, 5, 'ru',      'Самгёпсаль',          'ACTIVE', NOW(6), NOW(6)),
(45, 5, 'es',      'Panceta de cerdo a la parrilla','ACTIVE', NOW(6), NOW(6));

-- 6 떡볶이 / Tteokbokki
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(46, 6, 'zh-Hans', '辣炒年糕',   'ACTIVE', NOW(6), NOW(6)),
(47, 6, 'en',      'Tteokbokki', 'ACTIVE', NOW(6), NOW(6)),
(48, 6, 'ja',      'トッポッキ', 'ACTIVE', NOW(6), NOW(6)),
(49, 6, 'zh-Hant', '辣炒年糕',   'ACTIVE', NOW(6), NOW(6)),
(50, 6, 'vi',      'Bánh gạo cay','ACTIVE', NOW(6), NOW(6)),
(51, 6, 'id',      'Tteokbokki', 'ACTIVE', NOW(6), NOW(6)),
(52, 6, 'th',      'ต๊อกบกกี',     'ACTIVE', NOW(6), NOW(6)),
(53, 6, 'ru',      'Токпокки',   'ACTIVE', NOW(6), NOW(6)),
(54, 6, 'es',      'Tteokbokki', 'ACTIVE', NOW(6), NOW(6));

-- 7 김밥 / Gimbap
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(55, 7, 'zh-Hans', '紫菜包饭', 'ACTIVE', NOW(6), NOW(6)),
(56, 7, 'en',      'Gimbap',   'ACTIVE', NOW(6), NOW(6)),
(57, 7, 'ja',      'キンパ',   'ACTIVE', NOW(6), NOW(6)),
(58, 7, 'zh-Hant', '紫菜飯捲', 'ACTIVE', NOW(6), NOW(6)),
(59, 7, 'vi',      'Cơm cuộn rong biển','ACTIVE', NOW(6), NOW(6)),
(60, 7, 'id',      'Gimbap',   'ACTIVE', NOW(6), NOW(6)),
(61, 7, 'th',      'คิมบับ',     'ACTIVE', NOW(6), NOW(6)),
(62, 7, 'ru',      'Кимпап',   'ACTIVE', NOW(6), NOW(6)),
(63, 7, 'es',      'Gimbap',   'ACTIVE', NOW(6), NOW(6));

-- 8 잡채 / Japchae
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(64, 8, 'zh-Hans', '杂菜',          'ACTIVE', NOW(6), NOW(6)),
(65, 8, 'en',      'Japchae',       'ACTIVE', NOW(6), NOW(6)),
(66, 8, 'ja',      'チャプチェ',    'ACTIVE', NOW(6), NOW(6)),
(67, 8, 'zh-Hant', '雜菜',          'ACTIVE', NOW(6), NOW(6)),
(68, 8, 'vi',      'Miến trộn',     'ACTIVE', NOW(6), NOW(6)),
(69, 8, 'id',      'Japchae',       'ACTIVE', NOW(6), NOW(6)),
(70, 8, 'th',      'จับแช',           'ACTIVE', NOW(6), NOW(6)),
(71, 8, 'ru',      'Чапче',         'ACTIVE', NOW(6), NOW(6)),
(72, 8, 'es',      'Japchae',       'ACTIVE', NOW(6), NOW(6));

-- 9 순두부찌개 / Soft Tofu Stew
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(73, 9, 'zh-Hans', '嫩豆腐汤',           'ACTIVE', NOW(6), NOW(6)),
(74, 9, 'en',      'Soft Tofu Stew',     'ACTIVE', NOW(6), NOW(6)),
(75, 9, 'ja',      'スンドゥブチゲ',     'ACTIVE', NOW(6), NOW(6)),
(76, 9, 'zh-Hant', '嫩豆腐湯',           'ACTIVE', NOW(6), NOW(6)),
(77, 9, 'vi',      'Canh đậu hũ non',    'ACTIVE', NOW(6), NOW(6)),
(78, 9, 'id',      'Sup Tahu Lembut',    'ACTIVE', NOW(6), NOW(6)),
(79, 9, 'th',      'ซุปเต้าหู้อ่อน',         'ACTIVE', NOW(6), NOW(6)),
(80, 9, 'ru',      'Сундубу чигэ',       'ACTIVE', NOW(6), NOW(6)),
(81, 9, 'es',      'Guiso de Tofu Suave','ACTIVE', NOW(6), NOW(6));

-- 10 물냉면 / Cold Buckwheat Noodles
INSERT INTO food_name_translation (id, food_id, lang_code, name, status, created_at, updated_at) VALUES
(82, 10, 'zh-Hans', '冷面',                  'ACTIVE', NOW(6), NOW(6)),
(83, 10, 'en',      'Cold Buckwheat Noodles','ACTIVE', NOW(6), NOW(6)),
(84, 10, 'ja',      '水冷麺',                'ACTIVE', NOW(6), NOW(6)),
(85, 10, 'zh-Hant', '冷麵',                  'ACTIVE', NOW(6), NOW(6)),
(86, 10, 'vi',      'Mì lạnh',               'ACTIVE', NOW(6), NOW(6)),
(87, 10, 'id',      'Mie Dingin',            'ACTIVE', NOW(6), NOW(6)),
(88, 10, 'th',      'นังมยอนน้ำเย็น',          'ACTIVE', NOW(6), NOW(6)),
(89, 10, 'ru',      'Мульнэнмён',            'ACTIVE', NOW(6), NOW(6)),
(90, 10, 'es',      'Fideos Fríos de Alforfón','ACTIVE', NOW(6), NOW(6));

-- ============================================================
-- 5) FOOD ↔ INGREDIENT LINKS (shared ingredient pool reused)
--    inclusion_percent: likelihood across recipes; 응답 정렬은 서비스단에서 이 값 내림차순
-- ============================================================

-- 1 된장찌개: 된장(8), 두부(1), 애호박(11), 감자(12), 대파(5), 마늘(6)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(1,  1, 8,  100, 'ACTIVE', NOW(6), NOW(6)),
(2,  1, 1,  90,  'ACTIVE', NOW(6), NOW(6)),
(3,  1, 11, 80,  'ACTIVE', NOW(6), NOW(6)),
(4,  1, 12, 65,  'ACTIVE', NOW(6), NOW(6)),
(5,  1, 5,  85,  'ACTIVE', NOW(6), NOW(6)),
(6,  1, 6,  75,  'ACTIVE', NOW(6), NOW(6));

-- 2 김치찌개: 김치(4), 돼지고기(2), 두부(1), 대파(5), 고춧가루(10)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(7,  2, 4,  100, 'ACTIVE', NOW(6), NOW(6)),
(8,  2, 2,  85,  'ACTIVE', NOW(6), NOW(6)),
(9,  2, 1,  70,  'ACTIVE', NOW(6), NOW(6)),
(10, 2, 5,  80,  'ACTIVE', NOW(6), NOW(6)),
(11, 2, 10, 60,  'ACTIVE', NOW(6), NOW(6));

-- 3 비빔밥: 쌀밥(14), 시금치(16), 콩나물(17), 당근(15), 계란(13), 고추장(9)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(12, 3, 14, 100, 'ACTIVE', NOW(6), NOW(6)),
(13, 3, 16, 80,  'ACTIVE', NOW(6), NOW(6)),
(14, 3, 17, 80,  'ACTIVE', NOW(6), NOW(6)),
(15, 3, 15, 75,  'ACTIVE', NOW(6), NOW(6)),
(16, 3, 13, 85,  'ACTIVE', NOW(6), NOW(6)),
(17, 3, 9,  90,  'ACTIVE', NOW(6), NOW(6));

-- 4 불고기: 소고기(3), 간장(19), 양파(7), 설탕(20), 마늘(6), 참기름(18)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(18, 4, 3,  100, 'ACTIVE', NOW(6), NOW(6)),
(19, 4, 19, 90,  'ACTIVE', NOW(6), NOW(6)),
(20, 4, 7,  80,  'ACTIVE', NOW(6), NOW(6)),
(21, 4, 20, 75,  'ACTIVE', NOW(6), NOW(6)),
(22, 4, 6,  85,  'ACTIVE', NOW(6), NOW(6)),
(23, 4, 18, 70,  'ACTIVE', NOW(6), NOW(6));

-- 5 삼겹살: 돼지고기(2), 마늘(6), 대파(5), 김치(4)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(24, 5, 2,  100, 'ACTIVE', NOW(6), NOW(6)),
(25, 5, 6,  80,  'ACTIVE', NOW(6), NOW(6)),
(26, 5, 5,  60,  'ACTIVE', NOW(6), NOW(6)),
(27, 5, 4,  70,  'ACTIVE', NOW(6), NOW(6));

-- 6 떡볶이: 떡(21), 고추장(9), 어묵(22), 대파(5), 설탕(20)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(28, 6, 21, 100, 'ACTIVE', NOW(6), NOW(6)),
(29, 6, 9,  95,  'ACTIVE', NOW(6), NOW(6)),
(30, 6, 22, 80,  'ACTIVE', NOW(6), NOW(6)),
(31, 6, 5,  65,  'ACTIVE', NOW(6), NOW(6)),
(32, 6, 20, 60,  'ACTIVE', NOW(6), NOW(6));

-- 7 김밥: 쌀밥(14), 김(23), 단무지(24), 당근(15), 계란(13), 햄(25)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(33, 7, 14, 100, 'ACTIVE', NOW(6), NOW(6)),
(34, 7, 23, 100, 'ACTIVE', NOW(6), NOW(6)),
(35, 7, 24, 85,  'ACTIVE', NOW(6), NOW(6)),
(36, 7, 15, 80,  'ACTIVE', NOW(6), NOW(6)),
(37, 7, 13, 85,  'ACTIVE', NOW(6), NOW(6)),
(38, 7, 25, 70,  'ACTIVE', NOW(6), NOW(6));

-- 8 잡채: 당면(26), 소고기(3), 시금치(16), 당근(15), 버섯(27), 간장(19)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(39, 8, 26, 100, 'ACTIVE', NOW(6), NOW(6)),
(40, 8, 3,  70,  'ACTIVE', NOW(6), NOW(6)),
(41, 8, 16, 75,  'ACTIVE', NOW(6), NOW(6)),
(42, 8, 15, 80,  'ACTIVE', NOW(6), NOW(6)),
(43, 8, 27, 70,  'ACTIVE', NOW(6), NOW(6)),
(44, 8, 19, 85,  'ACTIVE', NOW(6), NOW(6));

-- 9 순두부찌개: 두부(1), 계란(13), 고춧가루(10), 대파(5), 마늘(6)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(45, 9, 1,  100, 'ACTIVE', NOW(6), NOW(6)),
(46, 9, 13, 80,  'ACTIVE', NOW(6), NOW(6)),
(47, 9, 10, 70,  'ACTIVE', NOW(6), NOW(6)),
(48, 9, 5,  75,  'ACTIVE', NOW(6), NOW(6)),
(49, 9, 6,  70,  'ACTIVE', NOW(6), NOW(6));

-- 10 물냉면: 메밀면(29), 육수(30), 오이(28), 계란(13), 소고기(3)
INSERT INTO food_ingredient (id, food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) VALUES
(50, 10, 29, 100, 'ACTIVE', NOW(6), NOW(6)),
(51, 10, 30, 95,  'ACTIVE', NOW(6), NOW(6)),
(52, 10, 28, 80,  'ACTIVE', NOW(6), NOW(6)),
(53, 10, 13, 75,  'ACTIVE', NOW(6), NOW(6)),
(54, 10, 3,  60,  'ACTIVE', NOW(6), NOW(6));
