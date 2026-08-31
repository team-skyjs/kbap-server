CREATE TABLE community_comment
(
    id         BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_id    BIGINT                    NOT NULL,
    member_id  BIGINT                    NOT NULL,
    parent_id  BIGINT                    NULL,
    content    VARCHAR(2000)             NOT NULL,
    edited_at  DATETIME(6)               NULL,
    status     ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)               NOT NULL,
    updated_at DATETIME(6)               NOT NULL,
    CONSTRAINT fk_community_comment_post FOREIGN KEY (post_id) REFERENCES community_post (id),
    CONSTRAINT fk_community_comment_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_community_comment_parent FOREIGN KEY (parent_id) REFERENCES community_comment (id),
    INDEX idx_community_comment_post_parent (post_id, parent_id, id),
    INDEX idx_community_comment_parent (parent_id)
);
