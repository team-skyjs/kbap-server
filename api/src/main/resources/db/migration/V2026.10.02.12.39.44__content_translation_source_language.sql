-- KB-688: 번역 캐시에 원문 언어를 함께 저장한다(짝 A — 스키마 선행). 가산만: NULL 허용 컬럼 하나, 기존 컬럼·값을 바꾸지 않는다.
-- 이 파일은 짝 B(코드) PR 에 byte 단위로 같게 동봉한다 — Flyway 체크섬은 주석까지 포함한다.
--
-- 번역 응답이 "○○어에서 번역"을 표시할 수 있게 원문 언어(BCP 47 코드)를 싣는데, 캐시 적중 때도 같은 값을 주려면
-- 번역문 옆에 저장해 둬야 한다. 엔진이 언어를 판별하지 못한 번역은 NULL 이다.
-- 앱이 아는 언어는 앱의 코드(ko, zh-Hans …)로, 그 밖은 언어 부분만 소문자로 저장한다 — 35자는 BCP 47 태그의 관례적 상한이다.
-- 문자셋·콜레이션은 표 기본값(utf8mb4 / utf8mb4_0900_ai_ci)을 따른다.
--
-- 구 코드와 공존한다: 구 코드의 INSERT … ON DUPLICATE KEY UPDATE 는 컬럼 목록을 명시하므로 이 컬럼은 NULL 로 남고,
-- 조회는 매핑된 컬럼만 읽는다. 기존 행은 NULL 로 시작한다 — 새 코드는 캐시 판(version)을 올려 그 행을 다시 번역한다.
ALTER TABLE content_translation
    ADD COLUMN source_language VARCHAR(35) NULL,
    ALGORITHM = INSTANT;
