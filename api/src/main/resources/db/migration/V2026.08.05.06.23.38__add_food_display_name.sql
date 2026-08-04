-- 표시 전용 이름(원본 표기, 띄어쓰기 포함). korean_name 은 중복 방지 match key 로 유지한다.
-- DEFAULT '' 는 raw INSERT 하위 호환용이며, 애플리케이션 쓰기 경로가 실제 값을 보장한다
-- (빈 값이 남아도 읽기 폴백 + 재적재 시 자가 치유 upsert 가 메운다).
ALTER TABLE food ADD COLUMN display_name VARCHAR(255) NOT NULL DEFAULT '' AFTER korean_name;

UPDATE food SET display_name = korean_name WHERE display_name = '';

-- V2026.07.21.12.06.47 정규화에서 건너뛴 행(정규화 시 이름이 사라져 보였던 행)의 마무리.
-- 위 백필로 원본 표기가 display_name 에 보존됐으므로 이제 무손실로 정규화할 수 있다.
-- unique 충돌 행은 병합 판단이 필요해 그대로 두며, 앱은 match key 로 조회하므로 이 행들은
-- 스캔 매칭에서 제외된 상태가 유지된다(수동 정리 대상).
UPDATE food f
LEFT JOIN food dup
    ON dup.korean_name = CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4)
   AND dup.id <> f.id
SET f.korean_name = CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4)
WHERE dup.id IS NULL
  AND CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4) <> ''
  AND f.korean_name <> CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4);
