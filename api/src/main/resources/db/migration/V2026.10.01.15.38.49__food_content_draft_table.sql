-- KB-673: 공개(READY) 음식의 재수집 결과를 공개 내용에 바로 반영하지 않고 초안으로 보관한다(짝 A — 스키마 선행).
-- 이 파일은 짝 B(코드) PR 에 byte 단위로 같게 동봉한다 — Flyway 체크섬은 주석까지 포함한다.
--
-- 가산만: 새 표 하나. 기존 표·값을 바꾸지 않는다.
-- 초안은 어드민 승인 시 food 에 반영되고(승인), 반려되면 공개 내용은 그대로다.
-- "음식당 PENDING 초안 ≤ 1" 은 유니크 키가 아니라 음식 행 잠금 아래 코드가 지킨다 — 대체·승인·반려 이력을 행으로 남기기 위해서다.
-- review_status 는 도메인 상태로, BaseEntity 의 소프트삭제 status 와 컬럼을 분리한다.
-- resolved_by 는 처리한 관리자 계정 id(report.handled_by 와 같은 방식, FK 없음).
CREATE TABLE food_content_draft
(
    id                       BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    food_id                  BIGINT                    NOT NULL,
    outbox_id                BIGINT                    NOT NULL,
    description              VARCHAR(255)              NOT NULL,
    long_description         VARCHAR(1000)             NULL,
    spiciness                INT                       NOT NULL,
    name_translations        JSON                      NOT NULL,
    description_translations JSON                      NOT NULL,
    ingredients              JSON                      NULL,
    review_status            VARCHAR(20)               NOT NULL DEFAULT 'PENDING',
    resolved_by              BIGINT                    NULL,
    resolved_at              DATETIME(6)               NULL,
    reject_reason            VARCHAR(500)              NULL,
    status                   ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at               DATETIME(6)               NOT NULL,
    updated_at               DATETIME(6)               NOT NULL,
    CONSTRAINT fk_food_content_draft_food FOREIGN KEY (food_id) REFERENCES food (id),
    CONSTRAINT fk_food_content_draft_outbox FOREIGN KEY (outbox_id) REFERENCES food_content_outbox (id),
    INDEX idx_food_content_draft_food_status (food_id, review_status),
    INDEX idx_food_content_draft_status_id (review_status, id)
);
