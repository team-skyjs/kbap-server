UPDATE member
SET profile = JSON_SET(profile, '$.profileImageUrl',
    TRIM(LEADING '/' FROM JSON_UNQUOTE(JSON_EXTRACT(profile, '$.profileImageUrl'))))
WHERE JSON_UNQUOTE(JSON_EXTRACT(profile, '$.profileImageUrl')) LIKE '/%';
