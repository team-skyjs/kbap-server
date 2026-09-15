-- KB-459: 재신고 허용(2026-09-15 예진 정책). 같은 사람이 같은 대상을 여러 번 신고하면 행이 여러 개
-- 쌓이고, 신고자에게는 첫 신고 즉시 그 콘텐츠가 숨겨진다. 플랫폼 관례(YouTube·Instagram·TikTok)와 같다.
--
-- 그래서 (reporter_member_id, target_type, target_id) 유니크를 제거한다. 제외 필터는 여전히
-- (신고자, target_type) 프리픽스로 조회하므로 같은 컬럼 순서의 일반 인덱스로 바꿔 효용을 유지한다.
-- 유니크 제거는 구 코드의 중복 방어를 없애는 변경이라, 재신고를 허용하는 코드와 같은 PR 에서 적용한다
-- (선행 마이그레이션 PR 은 가산 변경만 담는다).
ALTER TABLE `report`
    DROP INDEX `uk_report_reporter_target`,
    ADD INDEX `idx_report_reporter_member` (`reporter_member_id`, `target_type`);
