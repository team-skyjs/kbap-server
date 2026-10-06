-- KB-563: 어드민 신고 처리. 신고 행에 처리 상태·결과·처리자·시각·메모를 더한다(가산만).
-- handle_status: PENDING(기본) / HANDLED. 기존 행은 컬럼 기본값으로 PENDING 이 된다(기존 값 UPDATE 아님).
-- handle_result: DISMISSED / CONTENT_DELETED(처리 전 NULL). handled_by: 처리한 관리자 계정 id
-- (admin_account 와 FK 를 걸지 않는다 — 감사 기록은 계정 정리와 무관하게 보존).
-- 처리는 "같은 대상의 PENDING 전부"를 한 번에 HANDLED 로 바꾸므로 (target_type, target_id) 인덱스로
-- 그 대상 행만 잠그고, 목록은 (handle_status, id) 로 상태 필터 + 최근순을 탄다.
-- 구 코드는 새 컬럼을 모르고(엔티티 미매핑), INSERT 는 기본값으로 채워져 무해하다.
-- 컬럼 추가는 상수 기본값 컬럼을 맨 뒤에 더하는 것이라 INSTANT, 인덱스 추가는 INPLACE·동시 DML 허용 —
-- 둘 다 명시해 조건이 안 맞으면 더 무거운 알고리즘으로 조용히 떨어지지 않고 실패하게 한다.
ALTER TABLE `report`
    ADD COLUMN `handle_status` varchar(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN `handle_result` varchar(20) NULL,
    ADD COLUMN `handled_by` bigint NULL,
    ADD COLUMN `handled_at` datetime(6) NULL,
    ADD COLUMN `handle_note` varchar(500) NULL,
    ALGORITHM = INSTANT;

ALTER TABLE `report`
    ADD INDEX `idx_report_handle_status` (`handle_status`, `id`),
    ADD INDEX `idx_report_target` (`target_type`, `target_id`),
    ALGORITHM = INPLACE,
    LOCK = NONE;
