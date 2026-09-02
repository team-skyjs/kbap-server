-- 어드민 반려 전용 실패 유형 추가 — 파이프라인 실패(NOT_FOOD·JUDGE_REJECTED·INGREDIENT_GUARD)와
-- 관리자 검수 반려(ADMIN_REJECTED)를 상세/목록에서 구분 표기하기 위함(KB-406).
ALTER TABLE food
    MODIFY COLUMN content_failure_kind ENUM ('NOT_FOOD','JUDGE_REJECTED','INGREDIENT_GUARD','ADMIN_REJECTED') NULL;
