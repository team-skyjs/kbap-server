-- KB-439: 콘텐츠 READY 전이 시각 컬럼. ADD COLUMN 전용 — 백필 마이그레이션은 없다.
-- 미기록 READY 행은 읽기 시점에 createdAt(등록 시각) 폴백(Food.effectivePublishedAt)으로 근사한다.
-- 스키마만 선행 적용해 batch(validate-only) 배포 레이스를 회피한다.
ALTER TABLE `food`
    ADD COLUMN `published_at` datetime(6) NULL;
