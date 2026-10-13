-- KB-778: 홈 인기 음식 마케팅 풀. X-API-Version 1.2 이상의 홈은 조회 수 집계 대신 이 테이블의 food_id 중
-- READY 음식에서 호출마다 무작위 최대 10개를 내려준다. 행 = 풀에 든 음식 1개(운영자가 DB 에서 직접 관리).
-- food_id 유니크로 중복 등록을 막고, FK 로 존재하지 않는 음식을 막는다(소프트 삭제 구조라 ON DELETE 없음).
-- 비활성화는 행 삭제 또는 status=DELETED. 풀이 비면 1.2 이상 홈의 popularFoods 는 빈 목록이다(집계 폴백 없음).

CREATE TABLE food_marketing_popular
(
    id         BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    food_id    BIGINT                    NOT NULL,
    status     ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)               NOT NULL,
    updated_at DATETIME(6)               NOT NULL,
    CONSTRAINT uq_food_marketing_popular_food_id UNIQUE (food_id),
    CONSTRAINT fk_food_marketing_popular_food FOREIGN KEY (food_id) REFERENCES food (id)
);
