-- 회원 맵기 선호 정수(-1~10) → 6단계 enum 문자열 이관 (KB-262)
-- 컬럼 전환 아님 — member.profile JSON 내부 spicinessPreference 속성 재작성.

-- 1) 속성 결손 → 미설정(SKIP)
UPDATE member
SET profile = JSON_SET(profile, '$.spicinessPreference', 'SKIP')
WHERE JSON_EXTRACT(profile, '$.spicinessPreference') IS NULL;

-- 2) 정수(-1~10) → 단계 매핑
UPDATE member
SET profile = JSON_SET(
    profile,
    '$.spicinessPreference',
    CASE CAST(JSON_EXTRACT(profile, '$.spicinessPreference') AS SIGNED)
        WHEN -1 THEN 'SKIP'
        WHEN 0 THEN 'NONE'
        WHEN 1 THEN 'MILD'
        WHEN 2 THEN 'MILD'
        WHEN 3 THEN 'MILD'
        WHEN 4 THEN 'MEDIUM'
        WHEN 5 THEN 'MEDIUM'
        WHEN 6 THEN 'MEDIUM'
        WHEN 7 THEN 'HOT'
        WHEN 8 THEN 'HOT'
        WHEN 9 THEN 'EXTREME'
        WHEN 10 THEN 'EXTREME'
    END
)
WHERE JSON_TYPE(JSON_EXTRACT(profile, '$.spicinessPreference')) = 'INTEGER'
  AND CAST(JSON_EXTRACT(profile, '$.spicinessPreference') AS SIGNED) BETWEEN -1 AND 10;

-- 3) 가드: 6단계 외 값이 남아 있으면 profile NOT NULL 위반으로 마이그레이션을 실패시킨다(잔존 0행이면 no-op).
--    범위 밖 정수 등 비정상 데이터를 조용히 흡수하지 않기 위한 의도적 실패 장치.
UPDATE member
SET profile = NULL
WHERE JSON_UNQUOTE(JSON_EXTRACT(profile, '$.spicinessPreference'))
      NOT IN ('SKIP', 'NONE', 'MILD', 'MEDIUM', 'HOT', 'EXTREME');
