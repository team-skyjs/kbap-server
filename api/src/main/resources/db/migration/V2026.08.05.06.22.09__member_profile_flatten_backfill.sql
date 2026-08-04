-- 회원 프로필 JSON 평탄화 2/3 — profile JSON → 신규 컬럼 백필 (KB-297)
-- 소프트 삭제 회원 포함 전 행 대상(WHERE 없음).
-- JSON null 판정: JSON_TYPE = 'NULL'. 속성 자체가 없으면 JSON_EXTRACT 가 SQL NULL 을 반환해
-- else 분기의 JSON_UNQUOTE(NULL) 도 NULL 이 된다 — 두 경우 모두 컬럼 NULL 로 수렴.
-- profileImageUrl 은 legacy 선행 '/' 를 제거해 스토리지 키 컨벤션(무슬래시)으로 정규화한다.
-- spicinessPreference 는 전 회차 마이그레이션(V2026.07.30.15.02.02)이 6단계 enum 문자열 존재를
-- 보장한다 — 결손이면 NOT NULL 위반으로 시끄럽게 실패하는 것이 의도.

UPDATE member
SET spiciness_preference      = JSON_UNQUOTE(JSON_EXTRACT(profile, '$.spicinessPreference')),
    country_code              = IF(
        JSON_TYPE(JSON_EXTRACT(profile, '$.countryCode')) = 'NULL',
        NULL,
        JSON_UNQUOTE(JSON_EXTRACT(profile, '$.countryCode'))
    ),
    profile_image_url         = IF(
        JSON_TYPE(JSON_EXTRACT(profile, '$.profileImageUrl')) = 'NULL',
        NULL,
        TRIM(LEADING '/' FROM JSON_UNQUOTE(JSON_EXTRACT(profile, '$.profileImageUrl')))
    ),
    avoidance_substance_codes = COALESCE(JSON_EXTRACT(profile, '$.avoidanceSubstanceCodes'), JSON_ARRAY());
