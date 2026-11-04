-- S-960(KB-993): 음식에 실물 사진(어드민 presign·confirm 업로드)을 올린다. food_image.source 는 ENUM('GENERATED') 뿐이라 실물 사진을 구분할 값이 없다.
-- ENUM 끝에 'UPLOADED' 를 더한다. 기존 값·순서는 그대로라 기존 행(GENERATED)은 바뀌지 않는다. 구 코드는 UPLOADED 를 쓰지 않는다.
-- ⚠ 규칙상 MODIFY 라 예진 승인 뒤에만 머지한다(가산 원칙 예외 — 값 추가만, 축소·변경 없음).
-- ENUM 끝에 값을 더하는 변경은 MySQL 8 에서 INSTANT 다(저장 크기 1바이트 유지). 못 하면 조용히 COPY 로 가지 않고 실패하게 ALGORITHM 을 명시한다.
-- lock_wait_timeout 10초: INSTANT 도 배타 메타데이터 잠금을 잡는다 — 10초 안에 못 잡으면 실패시키고 재시도한다.
SET SESSION lock_wait_timeout = 10;

ALTER TABLE food_image
    MODIFY COLUMN source ENUM ('GENERATED', 'UPLOADED') NOT NULL DEFAULT 'GENERATED',
    ALGORITHM = INSTANT;

SET SESSION lock_wait_timeout = DEFAULT;
