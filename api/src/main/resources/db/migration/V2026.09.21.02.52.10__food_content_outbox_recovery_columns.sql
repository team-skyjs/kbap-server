-- KB-607: 콘텐츠 아웃박스 회수(짝 A — 스키마 선행). 가산 변경만 하며 구 코드와 공존한다.
-- 이 파일은 짝 B(코드) PR 에 byte 단위로 같게 동봉한다 — Flyway 체크섬은 주석까지 포함한다.
--
-- 배경: 발행된 SENT 행은 응답이 오지 않아도 다시 집히지 않는다. 퍼블리셔가 PENDING 만 읽기 때문이다.
-- prod 에서 173건이 2026-09-02~09-10 에 SENT 로 굳은 채 한 달 가까이 아무도 모르고 지나갔다.
--
-- outbox_status 에 값을 더하지 않는다. 기존 ENUM 컬럼 수정은 가산이 아니라서다.
-- 포기 상태는 dead_at 으로 표시한다 — 값이 있으면 회수 대상에서 빠지고 사람이 볼 대상이 된다.
-- last_error 는 왜 굳었는지를 남긴다. 숫자만 남으면 다음 사람이 조사를 처음부터 다시 한다.
ALTER TABLE food_content_outbox
    ADD COLUMN dead_at    DATETIME(6)  NULL,
    ADD COLUMN last_error VARCHAR(500) NULL,
    ADD INDEX idx_food_content_outbox_status_sent (outbox_status, sent_at);
