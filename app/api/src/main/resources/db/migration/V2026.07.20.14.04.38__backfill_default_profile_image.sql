UPDATE member
SET profile = JSON_SET(profile, '$.profileImageUrl', '/images/default/profile/profile-default-512.png')
WHERE JSON_EXTRACT(profile, '$.profileImageUrl') IS NULL
   OR JSON_TYPE(JSON_EXTRACT(profile, '$.profileImageUrl')) = 'NULL';
