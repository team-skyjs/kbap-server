-- korean_name 을 정규화(한글 외 문자 제거) 상태로 백필 — 매칭이 korean_name 정확 일치로 동작하는 전제.
-- 정규화 결과가 기존 행과 충돌(unique)하거나 빈 문자열이 되는 행은 건너뛴다(수동 정리 대상).
UPDATE food f
LEFT JOIN food dup
    ON dup.korean_name = CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4)
   AND dup.id <> f.id
SET f.korean_name = CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4)
WHERE dup.id IS NULL
  AND CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4) <> ''
  AND f.korean_name <> CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4);
