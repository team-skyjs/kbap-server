-- KB-302: 콘텐츠 수집 요청 아웃박스. 음식 등록과 같은 트랜잭션으로 쌓여 요청 유실을 막는다.
-- 발행(SQS)은 후속 티켓의 배치가 PENDING 행을 읽어 담당한다 — 이 테이블은 그 재료다.
-- outbox_status 는 도메인 상태로, BaseEntity 의 소프트삭제 status 와 컬럼을 분리한다.

CREATE TABLE food_content_outbox
(
    id            BIGINT                     NOT NULL AUTO_INCREMENT PRIMARY KEY,
    food_id       BIGINT                     NOT NULL,
    display_name  VARCHAR(255)               NOT NULL,
    outbox_status ENUM ('PENDING','SENT')    NOT NULL DEFAULT 'PENDING',
    attempts      INT                        NOT NULL DEFAULT 0,
    sent_at       DATETIME(6)                NULL,
    status        ENUM ('ACTIVE','DELETED')  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)                NOT NULL,
    updated_at    DATETIME(6)                NOT NULL,
    CONSTRAINT fk_food_content_outbox_food FOREIGN KEY (food_id) REFERENCES food (id),
    INDEX idx_food_content_outbox_status_id (outbox_status, id),
    INDEX idx_food_content_outbox_food (food_id)
);
