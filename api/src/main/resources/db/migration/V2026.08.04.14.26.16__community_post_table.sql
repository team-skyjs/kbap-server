CREATE TABLE community_post
(
    id         BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id  BIGINT                    NOT NULL,
    content    VARCHAR(2000)             NOT NULL,
    image_refs JSON                      NULL,
    food_ids   JSON                      NULL,
    edited_at  DATETIME(6)               NULL,
    status     ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)               NOT NULL,
    updated_at DATETIME(6)               NOT NULL,
    CONSTRAINT fk_community_post_member FOREIGN KEY (member_id) REFERENCES member (id)
);
