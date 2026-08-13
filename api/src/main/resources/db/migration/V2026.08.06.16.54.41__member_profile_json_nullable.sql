-- 회원 프로필 JSON 평탄화 3/3 — profile JSON 컬럼 nullable 전환 (KB-297)
-- 신규 코드는 이 컬럼을 더 이상 매핑·기록하지 않는다(신규 가입 행은 NULL).
-- 컬럼 drop 은 평탄화 안정화 확인 후 후속 릴리스로 미룬다(2026-08-06 결정).

ALTER TABLE `member` MODIFY COLUMN `profile` json NULL;
