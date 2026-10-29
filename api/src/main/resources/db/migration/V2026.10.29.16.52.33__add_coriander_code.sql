-- KB-895: 회피 재료 코드 CORIANDER(고수) 카탈로그 행 가산 + 향신채(PRODUCE, 파·마늘과 같은 분류) 지정.
-- image_path 는 비워 둔다 — S3 자산(ingredients-cut/CORIANDER.webp)이 아직 없다. 자산 업로드 뒤 data-only 마이그레이션으로 채운다(#451 방식).
-- 기존 행은 건드리지 않는다. 함의 없음(IngredientCode 쪽에도 IMPLICATIONS 없음).
-- 주의: ingredients.code 는 엔티티에서 enum 문자열 매핑이라, 롤링 배포 중 이 행을 구 태스크가 전체 조회하면 변환 예외가 난다(카탈로그 API, 수 분) — #446 선례대로 enum 과 같은 PR 로 낸다.
INSERT INTO ingredients (code, korean_name, translations) VALUES
('CORIANDER', '고수', '{"en": "Cilantro (coriander)", "es": "Cilantro", "id": "Ketumbar (daun)", "ja": "パクチー", "ru": "Кинза", "th": "ผักชี", "vi": "Rau mùi", "zh-Hans": "香菜", "zh-Hant": "香菜"}');

UPDATE `ingredients` SET `category_id` = (SELECT `id` FROM `ingredient_category` WHERE `code` = 'PRODUCE')
WHERE `code` IN ('CORIANDER');
