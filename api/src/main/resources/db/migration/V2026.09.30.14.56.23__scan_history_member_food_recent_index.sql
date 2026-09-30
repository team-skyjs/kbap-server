-- 홈 최근 스캔·스캔 음식 페이지·마지막 스캔 시각 조회는 전부 "회원 → 음식별 max(created_at)" 형태다.
-- (member_id, created_at) 로는 food_id GROUP BY 에 임시 테이블이 필요하므로
-- (member_id, food_id, created_at) 로 바꿔 그룹·MAX 를 인덱스 순서 그대로 읽게 한다 (KB-654).
ALTER TABLE scan_history
    DROP INDEX idx_scan_history_recent,
    ADD INDEX idx_scan_history_member_food_recent (member_id, food_id, created_at);
