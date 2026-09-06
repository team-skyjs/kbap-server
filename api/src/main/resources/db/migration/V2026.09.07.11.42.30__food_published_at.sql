-- KB-439: 콘텐츠 READY 전이 시각. 기존 READY 행은 updated_at 을 합리적 근사치로 백필한다
-- (READY 전이 이후 대부분 재수정이 없어 마지막 갱신 시각이 승인 시각에 가장 가깝다).
ALTER TABLE `food`
    ADD COLUMN `published_at` datetime(6) NULL;

UPDATE `food`
SET `published_at` = `updated_at`
WHERE `content_status` = 'READY'
  AND `published_at` IS NULL;
