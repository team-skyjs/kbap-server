-- KB-643: 음식 상세 조회 이력. 조회 1회 = 행 1개(append-only). 인기순 음식 집계의 원본 데이터.
-- created_at 이 조회 시각이다(비동기 저장 지연은 밀리초 수준). 검색·유입 경로는 범위 밖.
-- reserved_1~3 은 예비 컬럼(유입 경로 등 대비). 용도가 정해지면 RENAME COLUMN 으로 이름을 바꾸고 엔티티에 매핑한다.
-- food_id·member_id 에 FK 를 두지 않는다: 원본 행이 사라져도 이력은 남아야 하고, 비동기 저장이 원본 삭제와 경합하지 않게 한다.

CREATE TABLE food_view_log
(
    id         BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    food_id    BIGINT                    NOT NULL,
    member_id  BIGINT                    NULL,
    status     ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)               NOT NULL,
    updated_at DATETIME(6)               NOT NULL,
    reserved_1 VARCHAR(255)              NULL,
    reserved_2 VARCHAR(255)              NULL,
    reserved_3 VARCHAR(255)              NULL,
    INDEX idx_food_view_log_food_created (food_id, created_at)
);
