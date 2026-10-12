-- 홈 화면 부하 측정용 더미 데이터. 모든 행은 id >= 1000000000 으로 삽입해 cleanup-home-load.sql 이 id 범위로만 지운다.
-- 분량(DAU 1만·1년 가정의 80%): member 1,600 · food 4,000 · food_view_log 240,000(최근 30일) · food_review 40,000 · scan_history 160,000 · bookmark 16,000
-- 회원 3(k6 토큰 회원)에게 스캔 4,000행/음식 640종, 북마크 240개를 몰아줘 헤비 유저 케이스를 만든다.
SET SESSION cte_max_recursion_depth = 1000000;
SET @base = 1000000000;
SET @now = NOW(6);

INSERT INTO member (id, provider, provider_uid, email, nickname, profile, member_status, onboarding_completed, status, created_at, updated_at,
                    scan_count, review_count, unique_reviewed_food_count, spiciness_preference, country_code, avoidance_substance_codes, currency, diet_categories, scan_unlocked, is_bot)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 1600)
SELECT @base + i, 'APPLE', CONCAT('loadtest-', i), NULL, CONCAT('loadtest', i), NULL, 'ACTIVE', 1, 'ACTIVE', @now, @now,
       0, 0, 0, 'SKIP', 'KR', JSON_ARRAY(), 'KRW', JSON_ARRAY(), 0, 0
FROM n;

INSERT INTO food (id, korean_name, display_name, image_ref, description, name_translations, description_translations, spiciness, status, created_at, updated_at,
                  content_status, content_review_attempts, ingredients, version, long_description, published_at, ingredients_assessed)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 4000)
SELECT @base + i, CONCAT('LOADTEST-', i), CONCAT('LOADTEST-', i), NULL, CONCAT('loadtest description ', i),
       JSON_OBJECT('ko', CONCAT('LOADTEST-', i), 'en', CONCAT('Loadtest food ', i), 'ja', CONCAT('ロードテスト ', i), 'zh-Hans', CONCAT('负载测试 ', i), 'zh-Hant', CONCAT('負載測試 ', i),
                   'vi', CONCAT('Kiem tra tai ', i), 'id', CONCAT('Uji beban ', i), 'th', CONCAT('ทดสอบโหลด ', i), 'ru', CONCAT('Нагрузочный тест ', i), 'es', CONCAT('Prueba de carga ', i)),
       JSON_OBJECT('ko', REPEAT('설명 ', 20), 'en', REPEAT('description ', 20), 'ja', REPEAT('説明 ', 20), 'zh-Hans', REPEAT('描述 ', 20), 'zh-Hant', REPEAT('描述 ', 20),
                   'vi', REPEAT('mo ta ', 20), 'id', REPEAT('deskripsi ', 20), 'th', REPEAT('คำอธิบาย ', 20), 'ru', REPEAT('описание ', 20), 'es', REPEAT('descripcion ', 20)),
       i % 6, 'ACTIVE', @now, @now,
       'READY', 0,
       JSON_ARRAY(JSON_OBJECT('code', 'EGG', 'inclusion_percent', 10 + i % 80), JSON_OBJECT('code', 'MILK', 'inclusion_percent', 5 + i % 60),
                  JSON_OBJECT('code', 'PEANUT', 'inclusion_percent', 1 + i % 50), JSON_OBJECT('code', 'WALNUT', 'inclusion_percent', 1 + i % 40),
                  JSON_OBJECT('code', 'ALMOND', 'inclusion_percent', 1 + i % 30), JSON_OBJECT('code', 'CASHEW', 'inclusion_percent', 1 + i % 20),
                  JSON_OBJECT('code', 'PISTACHIO', 'inclusion_percent', 1 + i % 15), JSON_OBJECT('code', 'HAZELNUT', 'inclusion_percent', 1 + i % 12),
                  JSON_OBJECT('code', 'PECAN', 'inclusion_percent', 1 + i % 9), JSON_OBJECT('code', 'DAIRY', 'inclusion_percent', 1 + i % 7)),
       0, REPEAT('긴 설명 텍스트입니다. ', 40), @now, 1
FROM n;

INSERT INTO food_view_log (id, food_id, member_id, status, created_at, updated_at)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 240000)
SELECT @base + i, @base + 1 + FLOOR(POW(RAND(), 3) * 4000), IF(i % 3 = 0, NULL, @base + 1 + (i % 1600)), 'ACTIVE',
       @now - INTERVAL FLOOR(RAND() * 2592000) SECOND, @now
FROM n;

INSERT INTO food_review (id, member_id, food_id, rating, content, status, created_at, updated_at, version, author_country_code, language)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 40000)
SELECT @base + i, @base + 1 + (i % 1600), @base + 1 + FLOOR(POW(RAND(), 2) * 4000), 1 + (i % 5), CONCAT('loadtest review ', i), 'ACTIVE',
       @now - INTERVAL FLOOR(RAND() * 31536000) SECOND, @now, 0, 'KR', 'ko'
FROM n;

INSERT INTO scan_history (id, member_id, price, food_id, status, created_at, updated_at)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 160000)
SELECT @base + i,
       IF(i <= 4000, 3, @base + 1 + (i % 1600)),
       8000 + (i % 20) * 500,
       IF(i <= 4000, @base + 1 + (i % 640), @base + 1 + FLOOR(RAND() * 4000)),
       'ACTIVE',
       @now - INTERVAL FLOOR(RAND() * 31536000) SECOND, @now
FROM n;

INSERT INTO bookmark (id, member_id, food_id, status, created_at, updated_at)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 16000)
SELECT @base + i, IF(i <= 240, 3, @base + 1 + (i % 1600)), @base + 1 + ((i * 7) % 4000), 'ACTIVE',
       @now - INTERVAL FLOOR(RAND() * 31536000) SECOND, @now
FROM n;
