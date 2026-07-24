-- ShedLock 표준 스키마(KB-226) — 이미지 회수 @Scheduled 를 api 2대 중 1대만 실행하기 위한 락 테이블.
-- 락 저장소를 상태의 원천(MySQL)과 같은 계층에 둔다 — Redis 재시작·failover 에 영향받지 않는다.
CREATE TABLE `shedlock` (
  `name` varchar(64) NOT NULL,
  `lock_until` timestamp(3) NOT NULL,
  `locked_at` timestamp(3) NOT NULL,
  `locked_by` varchar(255) NOT NULL,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
