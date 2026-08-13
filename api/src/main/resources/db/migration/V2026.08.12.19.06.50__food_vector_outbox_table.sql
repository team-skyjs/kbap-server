-- KB-328: 음식 벡터 동기화 아웃박스. READY 전이·변경·삭제와 같은 트랜잭션으로 쌓여 반영 유실을 막는다.
-- 소비는 :batch 의 foodVectorSyncJob 이 PENDING 행을 읽어 DocumentDB 에 upsert/delete 한다.
-- outbox_status 는 도메인 상태로, BaseEntity 의 소프트삭제 status 와 컬럼을 분리한다.

CREATE TABLE food_vector_outbox
(
    id            BIGINT                              NOT NULL AUTO_INCREMENT PRIMARY KEY,
    food_id       BIGINT                              NOT NULL,
    operation     ENUM ('UPSERT','DELETE')            NOT NULL,
    outbox_status ENUM ('PENDING','COMPLETE','FAILED') NOT NULL DEFAULT 'PENDING',
    attempts      INT                                 NOT NULL DEFAULT 0,
    last_error    VARCHAR(500)                        NULL,
    status        ENUM ('ACTIVE','DELETED')           NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)                         NOT NULL,
    updated_at    DATETIME(6)                         NOT NULL,
    CONSTRAINT fk_food_vector_outbox_food FOREIGN KEY (food_id) REFERENCES food (id),
    INDEX idx_food_vector_outbox_status_id (outbox_status, id),
    INDEX idx_food_vector_outbox_food (food_id)
);
