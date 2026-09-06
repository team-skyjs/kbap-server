-- KB-439: 기존 READY 행 published_at 1회 백필. writer(approve 기록)와 같은 배포에서 실행돼
-- 선행 컬럼 추가(#238) 이후 승인된 행까지 빠짐없이 덮는다. updated_at 을 합리적 근사치로 쓴다
-- (READY 전이 이후 대부분 재수정이 없어 마지막 갱신 시각이 승인 시각에 가장 가깝다).
-- IS NULL 가드로 멱등 — approve() 가 기록한 실제 시각은 덮지 않는다.
UPDATE `food`
SET `published_at` = `updated_at`
WHERE `content_status` = 'READY'
  AND `published_at` IS NULL;
