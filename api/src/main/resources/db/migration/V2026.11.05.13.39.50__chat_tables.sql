-- KB-1007 운영진 1:1 채팅 — 사용자(회원) 또는 게스트 기기당 대화방 1개, 메시지는 USER/OPERATOR.
-- 가산 전용 신규 테이블 둘 + 지난 문의(feedback·feedback_reply) 를 대화방 이력으로 복사(INSERT 만, 원본 무변경).
-- 소유: 회원 방은 member_id, 게스트 방은 guest_installation_id(회원이 넘겨받으면 NULL 로 비운다 — 예진 11/5 승인 예외).
-- 읽음: 마지막으로 읽은 메시지 id(사용자·운영진 각각). 화면에 읽음 표시는 하지 않고 안읽음 수에만 쓴다.
-- last_device_info: 사용자가 마지막으로 보낸 메시지의 기기 정보(문의와 같은 허용 키 — locale·lang·os 등). 어드민 목록의 언어·국가 표시용.

CREATE TABLE chat_conversation
(
    id                            BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id                     BIGINT                    NULL,
    guest_installation_id         VARCHAR(36)               NULL,
    user_last_read_message_id     BIGINT                    NULL,
    operator_last_read_message_id BIGINT                    NULL,
    last_message_at               DATETIME(6)               NULL,
    last_user_message_at          DATETIME(6)               NULL,
    last_device_info              JSON                      NULL,
    status                        ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at                    DATETIME(6)               NOT NULL,
    updated_at                    DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_chat_conversation_member (member_id),
    UNIQUE KEY uk_chat_conversation_guest (guest_installation_id),
    KEY idx_chat_conversation_last_message (last_message_at),
    CONSTRAINT fk_chat_conversation_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE chat_message
(
    id                 BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id    BIGINT                    NOT NULL,
    author_role        ENUM ('USER','OPERATOR')  NOT NULL,
    admin_account_id   BIGINT                    NULL,
    content            TEXT                      NOT NULL,
    image_refs         JSON                      NULL,
    legacy_feedback_id BIGINT                    NULL,
    legacy_reply_id    BIGINT                    NULL,
    status             ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at         DATETIME(6)               NOT NULL,
    updated_at         DATETIME(6)               NOT NULL,
    KEY idx_chat_message_conversation (conversation_id, id),
    KEY idx_chat_message_legacy_feedback (legacy_feedback_id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_chat_message_admin FOREIGN KEY (admin_account_id) REFERENCES admin_account (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO chat_conversation (member_id, guest_installation_id, status, created_at, updated_at)
SELECT f.member_id, NULL, 'ACTIVE', MIN(f.created_at), NOW(6)
FROM feedback f
WHERE f.status = 'ACTIVE' AND f.member_id IS NOT NULL
GROUP BY f.member_id;

INSERT INTO chat_conversation (member_id, guest_installation_id, status, created_at, updated_at)
SELECT NULL, f.installation_id, 'ACTIVE', MIN(f.created_at), NOW(6)
FROM feedback f
WHERE f.status = 'ACTIVE' AND f.member_id IS NULL
GROUP BY f.installation_id;

INSERT INTO chat_message (conversation_id, author_role, admin_account_id, content, image_refs, legacy_feedback_id, legacy_reply_id, status, created_at, updated_at)
SELECT t.conversation_id, t.author_role, t.admin_account_id, t.content, t.image_refs, t.legacy_feedback_id, t.legacy_reply_id, 'ACTIVE', t.created_at, NOW(6)
FROM (
    SELECT c.id AS conversation_id, 'USER' AS author_role, NULL AS admin_account_id, f.content, f.image_refs,
           f.id AS legacy_feedback_id, NULL AS legacy_reply_id, f.created_at, 0 AS sort_order
    FROM feedback f
    JOIN chat_conversation c ON (f.member_id IS NOT NULL AND c.member_id = f.member_id)
                             OR (f.member_id IS NULL AND c.guest_installation_id = f.installation_id)
    WHERE f.status = 'ACTIVE'
    UNION ALL
    SELECT c.id, 'OPERATOR', r.admin_account_id, r.content, NULL, f.id, r.id, r.created_at, 1
    FROM feedback_reply r
    JOIN feedback f ON f.id = r.feedback_id AND f.status = 'ACTIVE'
    JOIN chat_conversation c ON (f.member_id IS NOT NULL AND c.member_id = f.member_id)
                             OR (f.member_id IS NULL AND c.guest_installation_id = f.installation_id)
    WHERE r.status = 'ACTIVE'
) t
ORDER BY t.created_at, t.sort_order, t.legacy_feedback_id;

UPDATE chat_conversation c
JOIN (
    SELECT conversation_id,
           MAX(id) AS last_id,
           MAX(created_at) AS last_at,
           MAX(CASE WHEN author_role = 'USER' THEN created_at END) AS last_user_at
    FROM chat_message
    GROUP BY conversation_id
) m ON m.conversation_id = c.id
SET c.user_last_read_message_id     = m.last_id,
    c.last_device_info              = (SELECT f.device_info FROM feedback f JOIN chat_message lm ON lm.legacy_feedback_id = f.id AND lm.legacy_reply_id IS NULL
                                       WHERE lm.conversation_id = c.id ORDER BY lm.id DESC LIMIT 1),
    c.operator_last_read_message_id = m.last_id,
    c.last_message_at               = m.last_at,
    c.last_user_message_at          = m.last_user_at;
