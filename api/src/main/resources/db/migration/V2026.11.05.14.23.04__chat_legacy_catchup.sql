-- KB-1007 채팅 이력 따라잡기 — 채팅 테이블 마이그레이션(SQL PR) 배포 뒤 코드 배포 전 사이에 들어온 문의·답변을
-- 대화방 이력으로 옮긴다. 이미 옮긴 행은 건너뛴다(NOT EXISTS — 멱등). 원본 feedback·feedback_reply 는 무변경.
-- 읽음 포인터는 건드리지 않는다: 새로 옮긴 사용자 문의는 운영진 안읽음으로, 답변은 사용자 안읽음으로 보인다(실제로 안 읽은 메시지).

INSERT INTO chat_conversation (member_id, guest_installation_id, status, created_at, updated_at)
SELECT f.member_id, NULL, 'ACTIVE', MIN(f.created_at), NOW(6)
FROM feedback f
WHERE f.status = 'ACTIVE' AND f.member_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM chat_conversation c WHERE c.member_id = f.member_id)
  AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.legacy_feedback_id = f.id AND m.legacy_reply_id IS NULL)
GROUP BY f.member_id;

INSERT INTO chat_conversation (member_id, guest_installation_id, status, created_at, updated_at)
SELECT NULL, f.installation_id, 'ACTIVE', MIN(f.created_at), NOW(6)
FROM feedback f
WHERE f.status = 'ACTIVE' AND f.member_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM chat_conversation c WHERE c.guest_installation_id = f.installation_id)
  AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.legacy_feedback_id = f.id AND m.legacy_reply_id IS NULL)
GROUP BY f.installation_id;

INSERT INTO chat_message (conversation_id, author_role, admin_account_id, content, image_refs, legacy_feedback_id, legacy_reply_id, status, created_at, updated_at)
SELECT t.conversation_id, t.author_role, t.admin_account_id, t.content, t.image_refs, t.legacy_feedback_id, t.legacy_reply_id, 'ACTIVE', t.created_at, NOW(6)
FROM (
    SELECT c.id AS conversation_id, 'USER' AS author_role, NULL AS admin_account_id, f.content, f.image_refs,
           f.id AS legacy_feedback_id, NULL AS legacy_reply_id, f.created_at, 0 AS sort_order
    FROM feedback f
    JOIN chat_conversation c ON (f.member_id IS NOT NULL AND c.member_id = f.member_id)
                             OR (f.member_id IS NULL AND c.guest_installation_id = f.installation_id AND c.member_id IS NULL)
    WHERE f.status = 'ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.legacy_feedback_id = f.id AND m.legacy_reply_id IS NULL)
    UNION ALL
    SELECT c.id, 'OPERATOR', r.admin_account_id, r.content, NULL, f.id, r.id, r.created_at, 1
    FROM feedback_reply r
    JOIN feedback f ON f.id = r.feedback_id AND f.status = 'ACTIVE'
    JOIN chat_conversation c ON (f.member_id IS NOT NULL AND c.member_id = f.member_id)
                             OR (f.member_id IS NULL AND c.guest_installation_id = f.installation_id AND c.member_id IS NULL)
    WHERE r.status = 'ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.legacy_reply_id = r.id)
) t
ORDER BY t.created_at, t.sort_order, t.legacy_feedback_id;

UPDATE chat_conversation c
JOIN (
    SELECT conversation_id,
           MAX(created_at) AS last_at,
           MAX(CASE WHEN author_role = 'USER' THEN created_at END) AS last_user_at
    FROM chat_message
    GROUP BY conversation_id
) m ON m.conversation_id = c.id
SET c.last_message_at      = m.last_at,
    c.last_user_message_at = m.last_user_at
WHERE c.last_message_at IS NULL OR c.last_message_at < m.last_at;
