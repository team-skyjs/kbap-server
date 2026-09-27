-- KB-646: 리뷰 봇 계정 표시. 봇은 리뷰를 채우는 운영 계정이라 랭킹·유저 통계(KB-645)에서 뺀다.
-- 가산만: NOT NULL DEFAULT 0 으로 기존 회원은 전부 0(사람)이 된다. 기존 값을 바꾸지 않는다.
ALTER TABLE `member`
  ADD COLUMN `is_bot` tinyint(1) NOT NULL DEFAULT 0;
