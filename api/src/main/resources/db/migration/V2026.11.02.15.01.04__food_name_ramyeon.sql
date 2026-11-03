-- KB-960(예진 11/2 결정): 한국 라면 음식 이름의 라틴 표기 로케일(en·es·vi·id)을 Ramen → Ramyeon 으로 맞춘다.

-- 일본식 라멘(korean_name 에 '라멘')은 제외. 다른 로케일·다른 키는 건드리지 않고, 키가 없는 로케일은 만들지 않는다(JSON_CONTAINS_PATH).

-- 데이터 전용 — 스키마 무변. 예진 요청이라 기존 값 갱신 승인(운영은 릴리스와 함께 적용).

UPDATE food
SET name_translations = JSON_SET(name_translations, '$.en', REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(name_translations, '$.en')), 'Ramen', 'Ramyeon'), 'ramen', 'ramyeon'))
WHERE korean_name LIKE '%라면%' AND korean_name NOT LIKE '%라멘%' AND JSON_CONTAINS_PATH(name_translations, 'one', '$.en');

UPDATE food
SET name_translations = JSON_SET(name_translations, '$.es', REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(name_translations, '$.es')), 'Ramen', 'Ramyeon'), 'ramen', 'ramyeon'))
WHERE korean_name LIKE '%라면%' AND korean_name NOT LIKE '%라멘%' AND JSON_CONTAINS_PATH(name_translations, 'one', '$.es');

UPDATE food
SET name_translations = JSON_SET(name_translations, '$.vi', REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(name_translations, '$.vi')), 'Ramen', 'Ramyeon'), 'ramen', 'ramyeon'))
WHERE korean_name LIKE '%라면%' AND korean_name NOT LIKE '%라멘%' AND JSON_CONTAINS_PATH(name_translations, 'one', '$.vi');

UPDATE food
SET name_translations = JSON_SET(name_translations, '$.id', REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(name_translations, '$.id')), 'Ramen', 'Ramyeon'), 'ramen', 'ramyeon'))
WHERE korean_name LIKE '%라면%' AND korean_name NOT LIKE '%라멘%' AND JSON_CONTAINS_PATH(name_translations, 'one', '$.id');
