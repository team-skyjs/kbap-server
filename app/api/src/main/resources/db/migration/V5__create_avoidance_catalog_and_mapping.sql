CREATE TABLE avoidance_substance (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(40)  NOT NULL,
    korean_name  VARCHAR(100) NOT NULL,
    name_zh_hans VARCHAR(100) NULL,
    name_en      VARCHAR(100) NULL,
    name_ja      VARCHAR(100) NULL,
    name_zh_hant VARCHAR(100) NULL,
    name_vi      VARCHAR(100) NULL,
    name_id      VARCHAR(100) NULL,
    name_th      VARCHAR(100) NULL,
    name_ru      VARCHAR(100) NULL,
    name_es      VARCHAR(100) NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_avoidance_substance_code (code)
);

CREATE TABLE avoidance_substance_category (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    substance_id BIGINT      NOT NULL,
    category     VARCHAR(30) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_asc_substance FOREIGN KEY (substance_id) REFERENCES avoidance_substance(id),
    CONSTRAINT ck_asc_category CHECK (category IN ('ALLERGEN', 'DIETARY_RULE', 'PERSONAL_AVOIDANCE')),
    UNIQUE KEY uq_avoidance_substance_category (substance_id, category)
);

CREATE TABLE ingredient_avoidance_substance (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    ingredient_id BIGINT      NOT NULL,
    substance_id  BIGINT      NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_ias_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient(id),
    CONSTRAINT fk_ias_substance FOREIGN KEY (substance_id) REFERENCES avoidance_substance(id),
    UNIQUE KEY uq_ingredient_avoidance_substance (ingredient_id, substance_id)
);

INSERT INTO avoidance_substance (code, korean_name, name_zh_hans, name_en, name_ja, name_zh_hant, name_vi, name_id, name_th, name_ru, name_es) VALUES
('EGG', '계란', '鸡蛋', 'Egg', '卵', '雞蛋', 'Trứng', 'Telur', 'ไข่', 'Яйцо', 'Huevo'),
('MILK', '우유', '牛奶', 'Milk', '牛乳', '牛奶', 'Sữa', 'Susu', 'นม', 'Молоко', 'Leche'),
('DAIRY', '유제품', '乳制品', 'Dairy products', '乳製品', '乳製品', 'Sản phẩm từ sữa', 'Produk susu', 'ผลิตภัณฑ์จากนม', 'Молочные продукты', 'Productos lácteos'),
('GOAT_MILK', '산양유', '山羊奶', 'Goat milk', '山羊乳', '山羊奶', 'Sữa dê', 'Susu kambing', 'นมแพะ', 'Козье молоко', 'Leche de cabra'),
('BUTTER', '버터', '黄油', 'Butter', 'バター', '奶油', 'Bơ', 'Mentega', 'เนย', 'Сливочное масло', 'Mantequilla'),
('GHEE', '기버터', '酥油', 'Ghee', 'ギー', '酥油', 'Bơ ghee', 'Ghee', 'กี', 'Гхи', 'Ghee'),
('CHEESE', '치즈', '奶酪', 'Cheese', 'チーズ', '起司', 'Phô mai', 'Keju', 'ชีส', 'Сыр', 'Queso'),
('GELATIN', '젤라틴', '明胶', 'Gelatin', 'ゼラチン', '明膠', 'Gelatin', 'Gelatin', 'เจลาติน', 'Желатин', 'Gelatina'),
('RENNET', '레닛', '凝乳酶', 'Rennet', 'レンネット', '凝乳酶', 'Men dịch vị', 'Rennet', 'เรนเนต', 'Сычужный фермент', 'Cuajo'),
('HONEY', '꿀', '蜂蜜', 'Honey', 'はちみつ', '蜂蜜', 'Mật ong', 'Madu', 'น้ำผึ้ง', 'Мёд', 'Miel'),
('CARMINE', '카민', '胭脂红', 'Carmine', 'カルミン', '胭脂紅', 'Carmine', 'Karmin', 'คาร์มีน', 'Кармин', 'Carmín'),
('PEANUT', '땅콩', '花生', 'Peanut', 'ピーナッツ', '花生', 'Đậu phộng', 'Kacang tanah', 'ถั่วลิสง', 'Арахис', 'Cacahuete'),
('WALNUT', '호두', '核桃', 'Walnut', 'くるみ', '核桃', 'Óc chó', 'Kenari', 'วอลนัท', 'Грецкий орех', 'Nuez'),
('PINE_NUT', '잣', '松子', 'Pine nut', '松の実', '松子', 'Hạt thông', 'Kacang pinus', 'เมล็ดสน', 'Кедровый орех', 'Piñón'),
('ALMOND', '아몬드', '杏仁', 'Almond', 'アーモンド', '杏仁', 'Hạnh nhân', 'Almond', 'อัลมอนด์', 'Миндаль', 'Almendra'),
('CASHEW', '캐슈넛', '腰果', 'Cashew nut', 'カシューナッツ', '腰果', 'Hạt điều', 'Kacang mete', 'เม็ดมะม่วงหิมพานต์', 'Кешью', 'Anacardo'),
('PISTACHIO', '피스타치오', '开心果', 'Pistachio', 'ピスタチオ', '開心果', 'Hạt dẻ cười', 'Pistachio', 'พิสตาชิโอ', 'Фисташка', 'Pistacho'),
('HAZELNUT', '헤이즐넛', '榛子', 'Hazelnut', 'ヘーゼルナッツ', '榛果', 'Hạt phỉ', 'Hazelnut', 'เฮเซลนัท', 'Фундук', 'Avellana'),
('MACADAMIA', '마카다미아', '夏威夷果', 'Macadamia nut', 'マカダミアナッツ', '夏威夷豆', 'Hạt mắc ca', 'Kacang macadamia', 'แมคคาเดเมีย', 'Макадамия', 'Macadamia'),
('PECAN', '피칸', '碧根果', 'Pecan', 'ピーカンナッツ', '碧根果', 'Hạt hồ đào', 'Pecan', 'พีแคน', 'Пекан', 'Pacana'),
('BRAZIL_NUT', '브라질너트', '巴西坚果', 'Brazil nut', 'ブラジルナッツ', '巴西堅果', 'Hạt Brazil', 'Kacang Brazil', 'บราซิลนัท', 'Бразильский орех', 'Nuez de Brasil'),
('CHESTNUT', '밤', '栗子', 'Chestnut', '栗', '栗子', 'Hạt dẻ', 'Kastanya', 'เกาลัด', 'Каштан', 'Castaña'),
('SESAME', '참깨', '芝麻', 'Sesame', 'ごま', '芝麻', 'Vừng', 'Wijen', 'งา', 'Кунжут', 'Sésamo'),
('SUNFLOWER_SEED', '해바라기씨', '葵花籽', 'Sunflower seed', 'ひまわりの種', '葵花籽', 'Hạt hướng dương', 'Biji bunga matahari', 'เมล็ดทานตะวัน', 'Семена подсолнечника', 'Semilla de girasol'),
('MUSTARD', '겨자', '芥末', 'Mustard', 'からし', '芥末', 'Mù tạt', 'Moster', 'มัสตาร์ด', 'Горчица', 'Mostaza'),
('WHEAT', '밀', '小麦', 'Wheat', '小麦', '小麥', 'Lúa mì', 'Gandum', 'ข้าวสาลี', 'Пшеница', 'Trigo'),
('BUCKWHEAT', '메밀', '荞麦', 'Buckwheat', 'そば', '蕎麥', 'Kiều mạch', 'Soba', 'บัควีท', 'Гречка', 'Trigo sarraceno'),
('BARLEY', '보리', '大麦', 'Barley', '大麦', '大麥', 'Lúa mạch', 'Jelai', 'ข้าวบาร์เลย์', 'Ячмень', 'Cebada'),
('RYE', '호밀', '黑麦', 'Rye', 'ライ麦', '黑麥', 'Lúa mạch đen', 'Rye', 'ไรย์', 'Рожь', 'Centeno'),
('OAT', '귀리', '燕麦', 'Oat', 'オーツ麦', '燕麥', 'Yến mạch', 'Oat', 'ข้าวโอ๊ต', 'Овёс', 'Avena'),
('CORN', '옥수수', '玉米', 'Corn', 'とうもろこし', '玉米', 'Ngô', 'Jagung', 'ข้าวโพด', 'Кукуруза', 'Maíz'),
('SOY', '대두', '大豆', 'Soybean', '大豆', '大豆', 'Đậu nành', 'Kedelai', 'ถั่วเหลือง', 'Соя', 'Soja'),
('LUPIN', '루핀', '羽扇豆', 'Lupin', 'ルピナス', '羽扇豆', 'Đậu lupin', 'Lupin', 'ลูปิน', 'Люпин', 'Altramuz'),
('PEA', '완두콩', '豌豆', 'Pea', 'えんどう豆', '豌豆', 'Đậu Hà Lan', 'Kacang polong', 'ถั่วลันเตา', 'Горох', 'Guisante'),
('CHICKPEA', '병아리콩', '鹰嘴豆', 'Chickpea', 'ひよこ豆', '鷹嘴豆', 'Đậu gà', 'Kacang arab', 'ถั่วลูกไก่', 'Нут', 'Garbanzo'),
('LENTIL', '렌틸콩', '扁豆', 'Lentil', 'レンズ豆', '扁豆', 'Đậu lăng', 'Lentil', 'ถั่วเลนทิล', 'Чечевица', 'Lenteja'),
('SHRIMP', '새우', '虾', 'Shrimp', 'エビ', '蝦', 'Tôm', 'Udang', 'กุ้ง', 'Креветка', 'Camarón'),
('SALTED_SHRIMP', '새우젓', '盐渍虾', 'Salted shrimp', 'アミの塩辛', '鹽漬蝦', 'Tôm muối', 'Udang asin fermentasi', 'กุ้งเค็มหมัก', 'Солёные креветки', 'Camarón salado fermentado'),
('CRAB', '게', '蟹', 'Crab', 'カニ', '蟹', 'Cua', 'Kepiting', 'ปู', 'Краб', 'Cangrejo'),
('CRAYFISH', '가재', '小龙虾', 'Crayfish', 'ザリガニ', '小龍蝦', 'Tôm càng', 'Lobster air tawar', 'เครย์ฟิช', 'Речной рак', 'Cangrejo de río'),
('LOBSTER', '랍스터', '龙虾', 'Lobster', 'ロブスター', '龍蝦', 'Tôm hùm', 'Lobster', 'ล็อบสเตอร์', 'Омар', 'Langosta'),
('SQUID', '오징어', '鱿鱼', 'Squid', 'イカ', '魷魚', 'Mực', 'Cumi-cumi', 'ปลาหมึก', 'Кальмар', 'Calamar'),
('OCTOPUS', '문어', '章鱼', 'Octopus', 'タコ', '章魚', 'Bạch tuộc', 'Gurita', 'หมึกยักษ์', 'Осьминог', 'Pulpo'),
('OYSTER', '굴', '牡蛎', 'Oyster', '牡蠣', '牡蠣', 'Hàu', 'Tiram', 'หอยนางรม', 'Устрица', 'Ostra'),
('OYSTER_SAUCE', '굴소스', '蚝油', 'Oyster sauce', 'オイスターソース', '蠔油', 'Dầu hào', 'Saus tiram', 'ซอสหอยนางรม', 'Устричный соус', 'Salsa de ostras'),
('ABALONE', '전복', '鲍鱼', 'Abalone', 'アワビ', '鮑魚', 'Bào ngư', 'Abalon', 'หอยเป๋าฮื้อ', 'Морское ушко', 'Abulón'),
('MUSSEL', '홍합', '贻贝', 'Mussel', 'ムール貝', '貽貝', 'Vẹm', 'Kerang hijau', 'หอยแมลงภู่', 'Мидия', 'Mejillón'),
('CLAM', '조개', '蛤蜊', 'Clam', '貝', '蛤蜊', 'Nghêu', 'Kerang', 'หอย', 'Моллюск', 'Almeja'),
('SHORT_NECK_CLAM', '바지락', '菲律宾蛤仔', 'Short-neck clam', 'アサリ', '菲律賓蛤仔', 'Nghêu cổ ngắn', 'Kerang asari', 'หอยลาย', 'Маленькая венерка', 'Almeja japonesa'),
('SCALLOP', '가리비', '扇贝', 'Scallop', 'ホタテ', '扇貝', 'Sò điệp', 'Kerang simping', 'หอยเชลล์', 'Морской гребешок', 'Vieira'),
('SEAFOOD', '해산물', '海鲜', 'Seafood', '海産物', '海鮮', 'Hải sản', 'Makanan laut', 'อาหารทะเล', 'Морепродукты', 'Mariscos'),
('FISH', '생선', '鱼', 'Fish', '魚', '魚', 'Cá', 'Ikan', 'ปลา', 'Рыба', 'Pescado'),
('MACKEREL', '고등어', '鲭鱼', 'Mackerel', 'サバ', '鯖魚', 'Cá thu', 'Ikan kembung', 'ปลาแมกเคอเรล', 'Скумбрия', 'Caballa'),
('SALMON', '연어', '三文鱼', 'Salmon', 'サーモン', '鮭魚', 'Cá hồi', 'Salmon', 'ปลาแซลมอน', 'Лосось', 'Salmón'),
('TUNA', '참치', '金枪鱼', 'Tuna', 'マグロ', '鮪魚', 'Cá ngừ', 'Tuna', 'ปลาทูน่า', 'Тунец', 'Atún'),
('COD', '대구', '鳕鱼', 'Cod', 'タラ', '鱈魚', 'Cá tuyết', 'Ikan kod', 'ปลาค็อด', 'Треска', 'Bacalao'),
('ANCHOVY', '멸치', '凤尾鱼', 'Anchovy', 'カタクチイワシ', '鳳尾魚', 'Cá cơm', 'Ikan teri', 'ปลาแอนโชวี่', 'Анчоус', 'Anchoa'),
('FISH_SAUCE', '액젓', '鱼露', 'Fish sauce', '魚醤', '魚露', 'Nước mắm', 'Kecap ikan', 'น้ำปลา', 'Рыбный соус', 'Salsa de pescado'),
('BROTH', '육수', '高汤', 'Broth', 'だし汁', '高湯', 'Nước dùng', 'Kaldu', 'น้ำซุป', 'Бульон', 'Caldo'),
('DASHI', '다시', '日式高汤', 'Dashi', '出汁', '日式高湯', 'Nước dùng dashi', 'Dashi', 'ดาชิ', 'Даси', 'Dashi'),
('BEEF', '소고기', '牛肉', 'Beef', '牛肉', '牛肉', 'Thịt bò', 'Daging sapi', 'เนื้อวัว', 'Говядина', 'Carne de res'),
('PORK', '돼지고기', '猪肉', 'Pork', '豚肉', '豬肉', 'Thịt heo', 'Daging babi', 'เนื้อหมู', 'Свинина', 'Carne de cerdo'),
('LARD', '라드', '猪油', 'Lard', 'ラード', '豬油', 'Mỡ heo', 'Lemak babi', 'น้ำมันหมู', 'Смалец', 'Manteca de cerdo'),
('TALLOW', '우지', '牛脂', 'Tallow', '牛脂', '牛脂', 'Mỡ bò', 'Lemak sapi', 'ไขมันวัว', 'Говяжий жир', 'Sebo'),
('CHICKEN', '닭고기', '鸡肉', 'Chicken', '鶏肉', '雞肉', 'Thịt gà', 'Daging ayam', 'เนื้อไก่', 'Курица', 'Pollo'),
('POULTRY', '가금류', '家禽', 'Poultry', '家禽類', '家禽', 'Gia cầm', 'Unggas', 'สัตว์ปีก', 'Птица', 'Aves de corral'),
('PEACH', '복숭아', '桃子', 'Peach', '桃', '桃子', 'Đào', 'Persik', 'ลูกพีช', 'Персик', 'Melocotón'),
('TOMATO', '토마토', '番茄', 'Tomato', 'トマト', '番茄', 'Cà chua', 'Tomat', 'มะเขือเทศ', 'Помидор', 'Tomate'),
('CELERY', '셀러리', '芹菜', 'Celery', 'セロリ', '芹菜', 'Cần tây', 'Seledri', 'ขึ้นฉ่าย', 'Сельдерей', 'Apio'),
('POTATO', '감자', '土豆', 'Potato', 'じゃがいも', '馬鈴薯', 'Khoai tây', 'Kentang', 'มันฝรั่ง', 'Картофель', 'Patata'),
('CARROT', '당근', '胡萝卜', 'Carrot', 'にんじん', '胡蘿蔔', 'Cà rốt', 'Wortel', 'แครอท', 'Морковь', 'Zanahoria'),
('ONION', '양파', '洋葱', 'Onion', '玉ねぎ', '洋蔥', 'Hành tây', 'Bawang bombai', 'หัวหอม', 'Лук', 'Cebolla'),
('GARLIC', '마늘', '大蒜', 'Garlic', 'にんにく', '大蒜', 'Tỏi', 'Bawang putih', 'กระเทียม', 'Чеснок', 'Ajo'),
('SCALLION', '파', '大葱', 'Scallion', '長ねぎ', '青蔥', 'Hành lá', 'Daun bawang', 'ต้นหอม', 'Зелёный лук', 'Cebolleta'),
('CHIVE', '부추', '韭菜', 'Chive', 'ニラ', '韭菜', 'Hẹ', 'Kucai', 'กุยช่าย', 'Шнитт-лук', 'Cebollino'),
('WILD_CHIVE', '달래', '野葱', 'Wild chive', 'ヒメニラ', '野蔥', 'Hẹ dại', 'Kucai liar', 'กุยช่ายป่า', 'Дикий шнитт-лук', 'Cebollino silvestre'),
('ASAFOETIDA', '흥거', '阿魏', 'Asafoetida', 'アサフェティダ', '阿魏', 'A nguỳ', 'Asafetida', 'มหาหิงคุ์', 'Асафетида', 'Asafétida'),
('ALCOHOL', '알코올', '酒精', 'Alcohol', 'アルコール', '酒精', 'Cồn', 'Alkohol', 'แอลกอฮอล์', 'Алкоголь', 'Alcohol'),
('MIRIN', '미림', '味醂', 'Mirin', 'みりん', '味醂', 'Rượu mirin', 'Mirin', 'มิริน', 'Мирин', 'Mirin'),
('COOKING_WINE', '맛술', '料酒', 'Cooking wine', '料理酒', '料理酒', 'Rượu nấu ăn', 'Anggur masak', 'ไวน์ปรุงอาหาร', 'Кулинарное вино', 'Vino de cocina'),
('SULFITES', '아황산류', '亚硫酸盐', 'Sulfites', '亜硫酸塩', '亞硫酸鹽', 'Sulfite', 'Sulfit', 'ซัลไฟต์', 'Сульфиты', 'Sulfitos');

INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'EGG';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'EGG';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'MILK';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'MILK';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'DAIRY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'DAIRY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'GOAT_MILK';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'GOAT_MILK';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'BUTTER';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'BUTTER';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'GHEE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'GHEE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CHEESE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'CHEESE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'GELATIN';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'RENNET';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'HONEY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'CARMINE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'PEANUT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'WALNUT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'PINE_NUT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'ALMOND';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CASHEW';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'PISTACHIO';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'HAZELNUT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'MACADAMIA';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'PECAN';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'BRAZIL_NUT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CHESTNUT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SESAME';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SUNFLOWER_SEED';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'MUSTARD';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'MUSTARD';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'WHEAT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'WHEAT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'BUCKWHEAT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'BARLEY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'BARLEY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'RYE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'RYE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'OAT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'OAT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CORN';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SOY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'LUPIN';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'PEA';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CHICKPEA';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'LENTIL';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SHRIMP';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'SHRIMP';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SALTED_SHRIMP';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'SALTED_SHRIMP';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'SALTED_SHRIMP';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CRAB';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'CRAB';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CRAYFISH';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'CRAYFISH';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'LOBSTER';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'LOBSTER';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SQUID';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'SQUID';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'SQUID';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'OCTOPUS';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'OCTOPUS';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'OCTOPUS';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'OYSTER';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'OYSTER';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'OYSTER';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'OYSTER_SAUCE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'OYSTER_SAUCE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'ABALONE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'ABALONE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'MUSSEL';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'MUSSEL';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'MUSSEL';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CLAM';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'CLAM';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SHORT_NECK_CLAM';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'SHORT_NECK_CLAM';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SCALLOP';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'SCALLOP';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SEAFOOD';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'SEAFOOD';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'SEAFOOD';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'FISH';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'FISH';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'FISH';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'MACKEREL';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'MACKEREL';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'MACKEREL';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SALMON';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'SALMON';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'TUNA';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'TUNA';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'COD';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'COD';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'ANCHOVY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'ANCHOVY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'ANCHOVY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'FISH_SAUCE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'FISH_SAUCE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'FISH_SAUCE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'BROTH';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'BROTH';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'DASHI';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'DASHI';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'BEEF';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'BEEF';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'BEEF';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'PORK';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'PORK';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'PORK';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'LARD';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'TALLOW';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CHICKEN';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'CHICKEN';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'CHICKEN';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'POULTRY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'POULTRY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'POULTRY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'PEACH';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'TOMATO';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'TOMATO';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'CELERY';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'POTATO';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'CARROT';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'ONION';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'ONION';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'GARLIC';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'GARLIC';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'SCALLION';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'SCALLION';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'CHIVE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'CHIVE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'WILD_CHIVE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'ASAFOETIDA';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'ALCOHOL';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'ALCOHOL';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'MIRIN';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'MIRIN';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'DIETARY_RULE' FROM avoidance_substance WHERE code = 'COOKING_WINE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'PERSONAL_AVOIDANCE' FROM avoidance_substance WHERE code = 'COOKING_WINE';
INSERT INTO avoidance_substance_category (substance_id, category) SELECT id, 'ALLERGEN' FROM avoidance_substance WHERE code = 'SULFITES';

-- mock ingredient ↔ avoidance/caution substance mapping (V3 seed ingredients × V5 catalog)
-- status/timestamps fall back to column DEFAULTs; join form avoids quoted tokens in parentheses
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '두부'     AND s.code = 'SOY';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '된장'     AND s.code = 'SOY';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '고추장'   AND s.code = 'SOY';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '간장'     AND s.code = 'SOY';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '간장'     AND s.code = 'WHEAT';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '돼지고기' AND s.code = 'PORK';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '햄'       AND s.code = 'PORK';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '소고기'   AND s.code = 'BEEF';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '계란'     AND s.code = 'EGG';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '마늘'     AND s.code = 'GARLIC';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '양파'     AND s.code = 'ONION';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '참기름'   AND s.code = 'SESAME';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '메밀면'   AND s.code = 'BUCKWHEAT';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '어묵'     AND s.code = 'FISH';
INSERT INTO ingredient_avoidance_substance (ingredient_id, substance_id) SELECT i.id, s.id FROM ingredient i, avoidance_substance s WHERE i.korean_name = '육수'     AND s.code = 'BROTH';
