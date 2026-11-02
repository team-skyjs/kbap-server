-- KB-966: 3.2 리뷰 목록 최신순이 작성일(created_at) 내림차순·동률 id 내림차순이다 — 정렬용 인덱스. 인덱스만 추가(엔티티 매핑 무변 — batch validate 영향 없음).
ALTER TABLE food_review ADD INDEX idx_food_review_status_created (status, created_at DESC, id DESC);
