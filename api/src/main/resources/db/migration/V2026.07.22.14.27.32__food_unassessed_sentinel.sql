-- 미조사 센티널: spiciness = -1, avoidance_substances = NULL.
-- CHECK 재정의가 백필보다 선행해야 -1 UPDATE 가 제약 위반으로 실패하지 않는다.
ALTER TABLE food DROP CHECK ck_food_spiciness;
ALTER TABLE food ADD CONSTRAINT ck_food_spiciness CHECK (spiciness BETWEEN -1 AND 10);

ALTER TABLE food MODIFY COLUMN avoidance_substances JSON NULL;

UPDATE food
SET spiciness = -1, avoidance_substances = NULL
WHERE content_status = 'INCOMPLETE';
