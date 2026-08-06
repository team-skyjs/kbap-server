-- 회원 프로필 JSON 평탄화 1/3 — 신규 컬럼 추가 (KB-297)
-- 기존 profile JSON 컬럼은 유지한다(구코드 호환) — 백필·drop 은 후속 마이그레이션.

ALTER TABLE `member`
    ADD COLUMN `spiciness_preference` enum('SKIP','NONE','MILD','MEDIUM','HOT','EXTREME') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SKIP',
    ADD COLUMN `country_code` varchar(2) COLLATE utf8mb4_unicode_ci NULL,
    ADD COLUMN `profile_image_url` varchar(512) COLLATE utf8mb4_unicode_ci NULL,
    ADD COLUMN `avoidance_substance_codes` json NOT NULL DEFAULT (JSON_ARRAY());
