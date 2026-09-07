-- 개명 로직 도입 전에 삭제된 행은 유니크 korean_name 을 그대로 쥐고 있어 동명 재등록이
-- 여전히 유니크 위반으로 막힌다 — 신규 삭제 경로와 같은 tombstone 규칙으로 일괄 개명한다.
-- 원명은 deleted_original_korean_name 에 보존하고, 이미 보존된 행은 건너뛴다(멱등).
UPDATE food
SET deleted_original_korean_name = korean_name,
    korean_name                  = CONCAT(
            LEFT(korean_name, 255 - CHAR_LENGTH(CONCAT('_deleted_', id))),
            '_deleted_', id)
WHERE status = 'DELETED'
  AND deleted_original_korean_name IS NULL;
