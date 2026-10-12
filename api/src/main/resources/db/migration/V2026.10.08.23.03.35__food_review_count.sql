-- KB-726: 홈 "리뷰 인기 음식" 레일이 요청마다 food_review(약 4만 행)를 group by 집계하던 것을
-- 음식별 리뷰 수 역정규화 컬럼으로 대체한다. 리뷰 작성·삭제 트랜잭션이 원자 UPDATE 로 증감한다.
-- 구 코드는 이 컬럼을 모른다 — DEFAULT 0 이라 구 리비전 INSERT 에 영향이 없고, 구 조회는 새 인덱스와 무관하다.
ALTER TABLE food
    ADD COLUMN review_count INT NOT NULL DEFAULT 0,
    ADD INDEX idx_food_review_count_recent (status, content_status, review_count DESC, id DESC);

-- 백필: 절대값 대입이라 재실행해도 결과가 같다. 배포 겹침 구간·운영 중 드리프트는 이 문장을 다시 실행해 복구한다.
UPDATE food f
LEFT JOIN (
    SELECT food_id, COUNT(*) AS cnt
    FROM food_review
    WHERE status = 'ACTIVE'
    GROUP BY food_id
) x ON x.food_id = f.id
SET f.review_count = COALESCE(x.cnt, 0);
