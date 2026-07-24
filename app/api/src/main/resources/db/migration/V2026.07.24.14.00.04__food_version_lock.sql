-- 낙관적 락(KB-226 리뷰 반영): 콘텐츠 배치(텍스트)와 이미지 회수가 같은 food 를 동시에 갱신할 수 있다.
-- detached 엔티티 전체 merge 가 상대 갱신(imageRef/텍스트)을 덮어쓰는 lost update 를 @Version 으로 검출한다.
-- DEFAULT 0 — 기존 행·수기 INSERT(시드/테스트) 모두 영향 없음.
ALTER TABLE food
    ADD COLUMN `version` bigint NOT NULL DEFAULT 0;
