-- 소프트삭제 개명 시 원명(매치키)을 그대로 보존하는 컬럼 — 255자 한도 근처 이름도
-- 접미 절단 없이 복원이 항상 정확한 원명을 되찾게 한다(KB-406 후속).
ALTER TABLE food
    ADD COLUMN deleted_original_korean_name VARCHAR(255) NULL AFTER korean_name;
