-- KB-606: 이미지 재생성의 의도를 제출 시점에 기록한다. 실패 처리만 이 값으로 갈린다.
--   REPLACE_BETTER — 더 나은 이미지로 교체: 실패하면 옛 이미지로 READY 복원
--   WRONG_IMAGE    — 잘못된 이미지: 실패해도 숨긴 채 유지
--   NULL           — 기존 행·의도 누락: WRONG_IMAGE 와 같이 숨긴 채 유지(가장 보수적인 쪽)
-- ENUM 이 아니라 VARCHAR 인 이유: 값이 늘 때 ENUM 은 MODIFY COLUMN 이 필요하다(DB 가산 변경 원칙).
-- regeneration_reason 은 어드민이 남기는 사유(KB-621). 마이그레이션을 두 번 내지 않으려고 같이 추가한다.
ALTER TABLE `image_batch_item`
  ADD COLUMN `regeneration_intent` varchar(20) NULL,
  ADD COLUMN `regeneration_reason` varchar(500) NULL;
