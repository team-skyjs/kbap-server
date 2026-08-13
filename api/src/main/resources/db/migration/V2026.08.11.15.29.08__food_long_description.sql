-- 벡터 DB 메타데이터용 긴 설명 — 사용자 노출 없음, kbap-langchain 적재 API 로만 채워진다
ALTER TABLE `food` ADD COLUMN `long_description` varchar(1000) NULL;
