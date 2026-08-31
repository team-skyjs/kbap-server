UPDATE member
SET profile = JSON_REMOVE(profile, '$.appLanguage')
WHERE JSON_CONTAINS_PATH(profile, 'one', '$.appLanguage');
