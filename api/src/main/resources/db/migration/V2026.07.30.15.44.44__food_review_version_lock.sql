-- 낙관적 락(KB-128): 같은 리뷰의 동시 수정/삭제 경합(중복 차감·삭제 되살림)을 @Version 으로 검출한다.
-- DEFAULT 0 — 기존 행·수기 INSERT(시드/테스트) 모두 영향 없음. (food_version_lock 선례)
ALTER TABLE `food_review`
    ADD COLUMN `version` bigint NOT NULL DEFAULT 0;
