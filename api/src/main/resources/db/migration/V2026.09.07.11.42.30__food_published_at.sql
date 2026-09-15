-- KB-439: 콘텐츠 READY 전이 시각 컬럼. 스키마만 선행 적용해 batch(validate-only) 배포 레이스를 회피한다.
-- 컬럼 추가 직후 기존 READY 행의 published_at 을 created_at(등록 시각)으로 백필한다 — published_at 이
-- NULL 인 READY 행 자체를 없애 "최초 공개 시각은 한 번 정해지면 재승인으로 바뀌지 않는다" 불변식을
-- 스키마 수준에서 보장한다. 백필이 없으면 레거시 READY 음식을 PENDING_REVIEW 로 되돌렸다 재승인할 때
-- published_at 에 현재 시각이 박혀 옛날 음식이 신규로 올라온다(Codex P2).
-- 백필은 기존 행의 값을 열 대 열로 복사할 뿐 시각 변환이 없어 타임존 해석에 영향을 주지 않는다.
ALTER TABLE `food`
    ADD COLUMN `published_at` datetime(6) NULL;

UPDATE `food`
SET `published_at` = `created_at`
WHERE `content_status` = 'READY'
  AND `published_at` IS NULL;
