-- KB-123: 회원 랭킹 점수의 원천 카운트 3종(랭킹 정책 2026-07-09).
--   score = 리뷰 수 × 10 + 리뷰한 고유 음식 수 × 5 + 스캔 수 × 2
-- 랭킹은 Member 애그리거트의 하위 개념이라 별도 카운터 테이블 대신 member 행에 컬럼으로 둔다
-- (가입 시 0 초기화 = DEFAULT 0, 이후 카운트업만. 탈퇴 시 회원과 함께 소프트 삭제된다).
-- 스캔 횟수는 "메뉴판 1장 = 1회" 단위다(scan_history 는 매칭된 음식마다 행이 생겨 횟수 집계에 쓸 수 없다).
-- 리뷰 도메인 도입 전이라 review_count·unique_reviewed_food_count 는 당분간 0에 머문다.
ALTER TABLE member
    ADD COLUMN scan_count                INT NOT NULL DEFAULT 0,
    ADD COLUMN review_count              INT NOT NULL DEFAULT 0,
    ADD COLUMN unique_reviewed_food_count INT NOT NULL DEFAULT 0;
