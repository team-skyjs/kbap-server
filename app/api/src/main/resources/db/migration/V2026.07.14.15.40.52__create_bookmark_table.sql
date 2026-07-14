-- KB-139: 회원별 음식 북마크(append-only). 등록마다 새 행을 INSERT 하고 취소는 소프트 삭제(status=DELETED)한다.
-- (member_id, food_id) 유일 제약 없음 — 재등록이 새 행이므로 같은 조합에 DELETED 다수 + ACTIVE 최대 1 이 공존한다.
-- 목록은 id DESC keyset 페이지네이션한다((member_id) 보조 인덱스는 InnoDB 가 PK 를 암묵 포함해 (member_id, id) 로 동작).
-- 공통 컬럼(status=EntityStatus, created_at, updated_at)은 BaseEntity(@MappedSuperclass)에서 온다.
CREATE TABLE bookmark (
    id         BIGINT                       NOT NULL AUTO_INCREMENT,
    member_id  BIGINT                       NOT NULL,
    food_id    BIGINT                       NOT NULL,
    status     ENUM ('ACTIVE', 'DELETED')   NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)                  NOT NULL,
    updated_at DATETIME(6)                  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_bookmark_member (member_id),
    CONSTRAINT fk_bookmark_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_bookmark_food FOREIGN KEY (food_id) REFERENCES food (id)
);
