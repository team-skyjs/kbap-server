-- KB-640: 음식별로 "사람이 검수했음"을 누가·언제 했는지 기록한다. 검수 큐 승인(approve)과는 별개의 수동 행위다.
-- 이력 테이블 없이 최신 검수자·시각만 둔다 — 다시 누르면 덮어쓰고, 해제하면 둘 다 NULL 로 돌아간다(수정 이력은 2단계).
-- admin_account.display_name 은 관리자 표시 이름(김예진·심종한). NULL 이면 로그인 아이디를 대신 보인다.
-- 가산만: ADD COLUMN·인덱스·FK 추가뿐이고 기존 값은 바꾸지 않는다. 구 코드는 이 컬럼을 모른다.
ALTER TABLE `admin_account`
  ADD COLUMN `display_name` varchar(50) NULL;

ALTER TABLE `food`
  ADD COLUMN `human_reviewed_by` bigint NULL,
  ADD COLUMN `human_reviewed_at` datetime(6) NULL,
  ADD INDEX `idx_food_human_reviewed` (`human_reviewed_by`, `human_reviewed_at`),
  ADD CONSTRAINT `fk_food_human_reviewed_by` FOREIGN KEY (`human_reviewed_by`) REFERENCES `admin_account` (`id`);
