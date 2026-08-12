-- KB-328: 도입 시점의 조회 가능(READY)·활성 음식을 벡터 적재 대기 건으로 1회 백필한다.
-- 이후 증분은 승인·수정·삭제 훅이 같은 트랜잭션에서 쌓는다.

INSERT INTO food_vector_outbox (food_id, operation, outbox_status, attempts, status, created_at, updated_at)
SELECT f.id, 'UPSERT', 'PENDING', 0, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM food f
WHERE f.content_status = 'READY'
  AND f.status = 'ACTIVE'
