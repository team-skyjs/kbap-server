ALTER TABLE food ADD COLUMN avoidance_substances JSON NULL;

UPDATE food f
SET f.avoidance_substances = COALESCE(
  (SELECT JSON_ARRAYAGG(JSON_OBJECT('code', s.substance_code, 'inclusion_percent', s.inclusion_percent))
   FROM food_avoidance_substance s
   WHERE s.food_id = f.id AND s.status = 'ACTIVE'),
  JSON_ARRAY());

ALTER TABLE food MODIFY COLUMN avoidance_substances JSON NOT NULL;
