-- KB-123: 회원 랭킹 점수의 원천 카운트. 랭킹은 Member 애그리거트의 하위 개념이라 별도 카운터 테이블 대신
-- member 행에 컬럼으로 둔다(가입 시 0 초기화 = DEFAULT 0, 이후 스캔마다 카운트업).
-- 스캔 횟수는 "메뉴판 1장 = 1회" 단위다(scan_history 는 매칭된 음식마다 행이 생겨 횟수 집계에 쓸 수 없다).
-- 리뷰 수·고유 음식 수는 리뷰 도메인 도입 시 같은 방식으로 컬럼을 추가한다(현재 점수 계산에서 0).
ALTER TABLE member
    ADD COLUMN scan_count INT NOT NULL DEFAULT 0;
