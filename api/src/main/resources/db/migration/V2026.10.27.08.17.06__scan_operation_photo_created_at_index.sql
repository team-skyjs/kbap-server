-- KB-879: 어드민 전체 스캔 사진 피드(최신순 커서)를 scan_operation_photo 에서 created_at 역순으로 읽기 위한 가산 인덱스.
SET SESSION lock_wait_timeout = 10;
ALTER TABLE scan_operation_photo ADD KEY idx_scan_photo_created (created_at), ALGORITHM = INPLACE, LOCK = NONE;
SET SESSION lock_wait_timeout = DEFAULT;
