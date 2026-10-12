-- seed-home-load.sql 이 넣은 행(id >= 1000000000)만 FK 자식 → 부모 순으로 지운다. 실제 데이터는 건드리지 않는다.
-- AUTO_INCREMENT 는 시드 전 값으로 되돌린다(아래 값은 시드 직전 기록: food 1270 · food_view_log 137 · food_review 51 · scan_history 12911 · bookmark 71 · member 43).
DELETE FROM scan_history WHERE id >= 1000000000;
DELETE FROM bookmark     WHERE id >= 1000000000;
DELETE FROM food_review  WHERE id >= 1000000000;
DELETE FROM food_view_log WHERE id >= 1000000000;
DELETE FROM food         WHERE id >= 1000000000;
DELETE FROM member       WHERE id >= 1000000000;
ALTER TABLE food          AUTO_INCREMENT = 1270;
ALTER TABLE food_view_log AUTO_INCREMENT = 137;
ALTER TABLE food_review   AUTO_INCREMENT = 51;
ALTER TABLE scan_history  AUTO_INCREMENT = 12911;
ALTER TABLE bookmark      AUTO_INCREMENT = 71;
ALTER TABLE member        AUTO_INCREMENT = 43;
