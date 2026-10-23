-- KB-836: 음식별 화제 영상 매핑. GET /api/foods/idol-picks 목록(음식 요약 + 대표 영상)의 원천.
-- 음식별 대표 영상 = ACTIVE 행 중 position 최소. 영상 URL·플랫폼·게시월·화제성(1~5)만 저장한다.
-- 시드는 food.korean_name 기준 INSERT ... SELECT 라 해당 음식이 없는 환경에서는 그 행만 들어가지 않는다.

CREATE TABLE food_idol_video
(
    id              BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    food_id         BIGINT                    NOT NULL,
    platform        ENUM ('YOUTUBE','TIKTOK') NOT NULL,
    video_url       VARCHAR(500)              NOT NULL,
    published_month DATE                      NOT NULL,
    hype            TINYINT                   NOT NULL,
    position        INT                       NOT NULL,
    status          ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6)               NOT NULL,
    updated_at      DATETIME(6)               NOT NULL,
    CONSTRAINT uq_food_idol_video_food_position UNIQUE (food_id, position),
    CONSTRAINT ck_food_idol_video_hype CHECK (hype BETWEEN 1 AND 5),
    CONSTRAINT fk_food_idol_video_food FOREIGN KEY (food_id) REFERENCES food (id)
);

INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=KOvl7TcdK98', '2026-07-01', 4, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '후라이드치킨';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'TIKTOK', 'https://www.tiktok.com/@popduckkk/video/7648253466552405269', '2026-06-01', 2, 2, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '후라이드치킨';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/shorts/VS17PUpMtN0', '2026-03-01', 4, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '삼겹살';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=7m09XumTnlc', '2025-10-01', 3, 2, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '삼겹살';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=2UM_XtWohrY', '2024-04-01', 3, 3, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '삼겹살';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=4gTHQqAKNA8', '2025-06-01', 4, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '떡볶이';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=_bsP4Kv0L1s', '2026-03-01', 3, 2, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '떡볶이';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'TIKTOK', 'https://www.tiktok.com/@e_vng_/video/7297258609140960545', '2023-11-01', 2, 3, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '떡볶이';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=5aQLQVrN8Fs', '2025-06-01', 4, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '한우구이';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=5ihFB4ZCnFY', '2024-10-01', 4, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '김치볶음밥';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=L47oQ6tesN0', '2024-08-01', 4, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '곱창구이';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=kxSaMtM1n3I', '2019-10-01', 4, 2, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '곱창구이';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=mXVJJeQTmrA', '2024-11-01', 3, 3, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '곱창구이';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=0gG2X8zI_oU', '2023-04-01', 4, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '막국수';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=w36BmMZH5cU', '2025-11-01', 3, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '부대찌개';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=CMGg27wKD0k', '2019-11-01', 3, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '간장게장';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=qywO4DAPzto', '2026-08-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '돼지국밥';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=feQxelV8Qe0', '2026-08-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '팥빙수';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=QOF2ePgh558', '2026-07-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '호떡';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=nhgKuk0tvcw', '2026-07-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '감자탕';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=ZQ0pfPl5IYU', '2026-06-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '안동찜닭';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=LwQSSzEJdN8', '2026-05-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '짬뽕';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=YzRDj5C1ey8', '2026-02-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '삼계탕';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=G3uU8uuIUGM', '2025-12-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '모듬회';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=MPncf57J8vY', '2025-12-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '갓김치';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=x2B5WFLHgtI', '2025-02-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '길거리토스트';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=IY9tChRxaF4', '2024-11-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '닭발';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=ZjcYFoGdBFA', '2024-07-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '제육볶음';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=5RKnXSQfpF8', '2024-07-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '밀면';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=-t0wcAEt8nM', '2024-04-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '짜장면';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=0yADNgx84_E', '2023-11-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '김치만두';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=75MRyHL2zJM', '2023-10-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '순대';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=yEgNGY2f6Xc', '2023-07-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '대게찜';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=u-Chuks0118', '2023-07-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '칼국수';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=VRpdJprNTro', '2023-06-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '돼지갈비';
INSERT INTO food_idol_video (food_id, platform, video_url, published_month, hype, position, status, created_at, updated_at)
SELECT f.id, 'YOUTUBE', 'https://www.youtube.com/watch?v=asYSYUJJwx0', '2023-06-01', 2, 1, 'ACTIVE', NOW(6), NOW(6) FROM food f WHERE f.korean_name = '족발';
