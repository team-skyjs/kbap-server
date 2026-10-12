-- 회원의 스캔 이력 조회는 전부 member_id = ? AND status = 'ACTIVE' 로 시작해 food_id 로 묶고 MAX(created_at) 을 구한다.
-- (member_id, food_id, created_at) 에는 status 가 없어 스캔 행마다 본 테이블을 읽었다.
-- status 를 넣어 음식별 마지막 스캔 시각 집계를 인덱스만으로 끝낸다 (KB-717).
-- 구 코드의 쿼리도 member_id·status 등치 조건을 가지므로 배포 중 이 인덱스를 그대로 탄다.
ALTER TABLE scan_history
    DROP INDEX idx_scan_history_member_food_recent,
    ADD INDEX idx_scan_history_member_status_food_recent (member_id, status, food_id, created_at);
