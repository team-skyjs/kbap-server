SELECT CASE
           WHEN COUNT(*) = 1 THEN 'READY'
           ELSE 'INVALID: member 35 must exist, be ACTIVE, and have scan_unlocked = 1'
       END AS fixture_status
FROM member
WHERE id = 35
  AND member_status = 'ACTIVE'
  AND status = 'ACTIVE'
  AND scan_unlocked = 1;

SELECT id AS target_member_id, nickname
FROM member
WHERE id <> 35
  AND member_status = 'ACTIVE'
  AND status = 'ACTIVE'
ORDER BY id
LIMIT 20;

SELECT DISTINCT f.id AS scanned_food_id, f.display_name
FROM scan_history sh
JOIN food f ON f.id = sh.food_id
WHERE sh.member_id = 35
  AND sh.status = 'ACTIVE'
  AND f.status = 'ACTIVE'
  AND f.content_status = 'READY'
ORDER BY f.id
LIMIT 20;

SELECT id AS owned_review_id, food_id, content
FROM food_review
WHERE member_id = 35
  AND status = 'ACTIVE'
ORDER BY id DESC
LIMIT 20;

SELECT id AS report_or_like_review_id, member_id, food_id, content
FROM food_review
WHERE member_id <> 35
  AND status = 'ACTIVE'
ORDER BY id DESC
LIMIT 20;
