-- S-844(KB-842 후속, 예진 R-106 (a) 승인 — 기존 값 UPDATE 예외): 식단을 고른 기존 회원의 저장 회피 코드에
-- KB-842 에서 DietCategory 매핑에 새로 들어간 코드를 같은 규칙으로 가산한다.
--   고기 회피 식단 7종(VEGAN·VEGETARIAN·LACTO_VEGETARIAN·OVO_VEGETARIAN·PESCATARIAN·BUDDHIST·JAIN) → MEAT·LAMB
--   SHELLFISH_ALLERGY → CRUSTACEAN·MOLLUSK
-- (MEAT_BROTH·GELATIN 은 식단에 이미 있는 BROTH·BEEF·PORK 의 함의로 걸려 저장하지 않는다. NUT_ALLERGY 는 NUTS 미추가 — 땅콩을 끌어오므로.)
-- 가산만: 이미 있으면 무변, 제거 없음, 식단 없는 회원·삭제 회원 무변, updated_at 보존. 다시 돌려도 무변(멱등).
-- 되돌리기(앱이 새 코드를 노출하기 전(KB-843)이라 이 4코드는 이 백필로만 들어간다):
--   UPDATE member SET avoidance_substance_codes = JSON_REMOVE(avoidance_substance_codes, JSON_UNQUOTE(JSON_SEARCH(avoidance_substance_codes, 'one', '<CODE>'))), updated_at = updated_at
--   WHERE JSON_CONTAINS(avoidance_substance_codes, JSON_QUOTE('<CODE>'));  -- CODE 마다(MEAT·LAMB·CRUSTACEAN·MOLLUSK)
UPDATE member
SET avoidance_substance_codes = JSON_ARRAY_APPEND(avoidance_substance_codes, '$', 'MEAT'), updated_at = updated_at
WHERE status = 'ACTIVE'
  AND JSON_OVERLAPS(diet_categories, JSON_ARRAY('VEGAN', 'VEGETARIAN', 'LACTO_VEGETARIAN', 'OVO_VEGETARIAN', 'PESCATARIAN', 'BUDDHIST', 'JAIN'))
  AND NOT JSON_CONTAINS(avoidance_substance_codes, JSON_QUOTE('MEAT'));

UPDATE member
SET avoidance_substance_codes = JSON_ARRAY_APPEND(avoidance_substance_codes, '$', 'LAMB'), updated_at = updated_at
WHERE status = 'ACTIVE'
  AND JSON_OVERLAPS(diet_categories, JSON_ARRAY('VEGAN', 'VEGETARIAN', 'LACTO_VEGETARIAN', 'OVO_VEGETARIAN', 'PESCATARIAN', 'BUDDHIST', 'JAIN'))
  AND NOT JSON_CONTAINS(avoidance_substance_codes, JSON_QUOTE('LAMB'));

UPDATE member
SET avoidance_substance_codes = JSON_ARRAY_APPEND(avoidance_substance_codes, '$', 'CRUSTACEAN'), updated_at = updated_at
WHERE status = 'ACTIVE'
  AND JSON_OVERLAPS(diet_categories, JSON_ARRAY('SHELLFISH_ALLERGY'))
  AND NOT JSON_CONTAINS(avoidance_substance_codes, JSON_QUOTE('CRUSTACEAN'));

UPDATE member
SET avoidance_substance_codes = JSON_ARRAY_APPEND(avoidance_substance_codes, '$', 'MOLLUSK'), updated_at = updated_at
WHERE status = 'ACTIVE'
  AND JSON_OVERLAPS(diet_categories, JSON_ARRAY('SHELLFISH_ALLERGY'))
  AND NOT JSON_CONTAINS(avoidance_substance_codes, JSON_QUOTE('MOLLUSK'));
