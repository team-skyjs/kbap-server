-- KB-349: 환율 출처를 frankfurter(ECB, 30종) 실시간 조회로 전환하며 취급 통화를 축소한다.
-- 제공처 미지원으로 폐기된 18종을 가진 기존 회원을 USD 로 일괄 이관한다.
-- IS(아이슬란드)·RO(루마니아) 회원의 기존 USD 는 건드리지 않는다 — 사용자가 고른(또는 지정된) 값을
-- 말없이 바꾸지 않는 KB-322 원칙. 신규 온보딩부터 ISK·RON 이 지정된다.
-- 폐기 집합은 CurrencyRemapSyncTest 가 KB-322 백필 SQL·CurrencyCode enum 과 대조한다.
UPDATE `member`
SET `currency` = 'USD'
WHERE `currency` IN ('AED','BDT','BHD','BND','EGP','FJD','JOD','KHR','KWD','KZT','MNT','NPR','PKR','QAR','RUB','SAR','TWD','VND');
