-- KB-717 홈 최근 스캔 조회 후보 비교용. 회원 3(시드 4,477행·853종) 기준으로 한 블록씩 실행한다.
-- [읽기 전용] 표시 블록은 dev 에서 그대로 실행해도 된다. [로컬 전용] 블록은 인덱스·테이블을 만들므로 dev 에서 실행하지 않는다
-- (dev 에 손으로 만든 인덱스는 Flyway 마이그레이션과 이름이 충돌한다).
-- 통계가 낡았으면 플랜이 왜곡된다: ANALYZE TABLE scan_history, food;
SET @member = 3;

-- ─────────────────────────────────────────────────────────────────────────────
-- (A) 현재 쿼리 [읽기 전용] — 조인 후 group by. 기준선.
-- ─────────────────────────────────────────────────────────────────────────────
EXPLAIN ANALYZE
SELECT f.*, MAX(sh.created_at) AS scanned_at
FROM scan_history sh
JOIN food f ON f.id = sh.food_id AND f.status = 'ACTIVE'
WHERE sh.member_id = @member
  AND sh.status = 'ACTIVE'
  AND f.content_status = 'READY'
GROUP BY f.id
ORDER BY MAX(sh.created_at) DESC
LIMIT 10;

-- ─────────────────────────────────────────────────────────────────────────────
-- (B) CTE 선집계 후 음식 PK 결합 [읽기 전용] — 이 브랜치의 쿼리. 인덱스가 구 인덱스면 B, 새 인덱스면 C 다.
-- 볼 것: food 가 eq_ref(PRIMARY) 로만 붙는가, 임시 테이블이 (food_id, max) 두 컬럼인가.
-- ─────────────────────────────────────────────────────────────────────────────
EXPLAIN ANALYZE
WITH recent AS (
    SELECT sh.food_id, MAX(sh.created_at) AS scanned_at
    FROM scan_history sh
    WHERE sh.member_id = @member
      AND sh.status = 'ACTIVE'
    GROUP BY sh.food_id
)
SELECT f.*, r.scanned_at
FROM recent r
JOIN food f ON f.id = r.food_id
WHERE f.status = 'ACTIVE'
  AND f.content_status = 'READY'
ORDER BY r.scanned_at DESC
LIMIT 10;

-- ─────────────────────────────────────────────────────────────────────────────
-- (C) B + 커버링 인덱스 [로컬 전용] — 이 브랜치의 마이그레이션과 같은 변경.
-- 볼 것: scan_history 가 Using index(본 테이블 미조회)인가, Using index for group-by 또는 임시 테이블 없는 그룹핑인가.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE scan_history
    DROP INDEX idx_scan_history_member_food_recent,
    ADD INDEX idx_scan_history_member_status_food_recent (member_id, status, food_id, created_at);
ANALYZE TABLE scan_history;
-- → (B) 블록을 다시 실행한다.

-- 범위 밖 쿼리가 새 인덱스에서 느려지지 않는지 (FR-006)
EXPLAIN ANALYZE
SELECT t.food_id FROM (
    SELECT sh.food_id AS food_id, MAX(sh.created_at) AS last_scanned_at
    FROM scan_history sh JOIN food f ON f.id = sh.food_id
    WHERE sh.member_id = @member AND sh.status = 'ACTIVE' AND f.status = 'ACTIVE' AND f.content_status = 'READY'
    GROUP BY sh.food_id
) t
ORDER BY t.last_scanned_at DESC, t.food_id DESC
LIMIT 21;

EXPLAIN ANALYZE
SELECT MAX(sh.created_at) FROM scan_history sh
WHERE sh.member_id = @member AND sh.food_id = 1000000390 AND sh.status = 'ACTIVE';

-- ─────────────────────────────────────────────────────────────────────────────
-- (D) 최신 행부터 음식 중복 제거(윈도 함수) [로컬 전용] — Jira 후보 2.
-- 볼 것: 윈도 함수가 회원의 전 행을 읽는가(읽는다면 C 대비 이득 없음).
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE scan_history ADD INDEX tmp_scan_history_member_status_recent (member_id, status, created_at DESC, food_id);
ANALYZE TABLE scan_history;

EXPLAIN ANALYZE
WITH ranked AS (
    SELECT sh.food_id, sh.created_at AS scanned_at,
           ROW_NUMBER() OVER (PARTITION BY sh.food_id ORDER BY sh.created_at DESC) AS rn
    FROM scan_history sh
    WHERE sh.member_id = @member
      AND sh.status = 'ACTIVE'
)
SELECT f.*, r.scanned_at
FROM ranked r
JOIN food f ON f.id = r.food_id
WHERE r.rn = 1
  AND f.status = 'ACTIVE'
  AND f.content_status = 'READY'
ORDER BY r.scanned_at DESC
LIMIT 10;

ALTER TABLE scan_history DROP INDEX tmp_scan_history_member_status_recent;

-- ─────────────────────────────────────────────────────────────────────────────
-- (E) 읽기 모델 member_scanned_food [로컬 전용] — 쿼리 레벨이 목표에 못 미칠 때의 후속안.
-- 회원×음식당 한 행. 인덱스 순서로 10행 남짓만 읽고 끝난다(비용이 누적 스캔 수와 무관).
-- 볼 것: 임시 테이블·정렬 없이 인덱스 역순 스캔 + food eq_ref 인가, 검사 행 수가 10 근처인가.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE tmp_member_scanned_food (
    member_id       BIGINT      NOT NULL,
    food_id         BIGINT      NOT NULL,
    last_scanned_at DATETIME(6) NOT NULL,
    scan_count      INT         NOT NULL,
    PRIMARY KEY (member_id, food_id),
    KEY idx_member_last_scanned (member_id, last_scanned_at DESC, food_id)
);

INSERT INTO tmp_member_scanned_food (member_id, food_id, last_scanned_at, scan_count)
SELECT member_id, food_id, MAX(created_at), COUNT(*)
FROM scan_history
WHERE status = 'ACTIVE' AND food_id IS NOT NULL
GROUP BY member_id, food_id;
ANALYZE TABLE tmp_member_scanned_food;

EXPLAIN ANALYZE
SELECT f.*, m.last_scanned_at
FROM tmp_member_scanned_food m
JOIN food f ON f.id = m.food_id
WHERE m.member_id = @member
  AND f.status = 'ACTIVE'
  AND f.content_status = 'READY'
ORDER BY m.last_scanned_at DESC
LIMIT 10;

DROP TABLE tmp_member_scanned_food;

-- ─────────────────────────────────────────────────────────────────────────────
-- 로컬 원복 — (C) 의 인덱스를 구 인덱스로 되돌린다(Flyway 가 다시 적용할 수 있게).
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE scan_history
    DROP INDEX idx_scan_history_member_status_food_recent,
    ADD INDEX idx_scan_history_member_food_recent (member_id, food_id, created_at);
