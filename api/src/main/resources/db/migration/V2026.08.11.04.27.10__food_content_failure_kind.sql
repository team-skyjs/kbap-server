-- KB-302: 랭체인이 돌려준 판정 실패의 유형을 저장한다. 사유 문구는 기존 content_review_rejection_reason 을 재사용한다.
-- NULL = 실패 이력 없음(성공 적재 시 NULL 로 초기화). 구 코드가 신 스키마 위에서 도는 배포 구간을 위해 NULL 허용이 필수다.

ALTER TABLE food
    ADD COLUMN content_failure_kind ENUM('NOT_FOOD','JUDGE_REJECTED','INGREDIENT_GUARD') NULL AFTER content_review_rejection_reason;
