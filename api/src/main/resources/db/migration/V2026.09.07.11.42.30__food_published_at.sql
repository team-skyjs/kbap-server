-- KB-439: 콘텐츠 READY 전이 시각 컬럼. 스키마만 선행 적용한다(배치 배포 레이스 회피).
-- 기존 READY 행 백필은 writer(approve 기록)와 같은 배포에서 돌도록 본 PR(#236)의
-- 별도 마이그레이션이 수행한다 — 선행 백필 뒤 writer 배포 전에 승인된 행이 영구 NULL 로 남는 갭 방지.
ALTER TABLE `food`
    ADD COLUMN `published_at` datetime(6) NULL;
