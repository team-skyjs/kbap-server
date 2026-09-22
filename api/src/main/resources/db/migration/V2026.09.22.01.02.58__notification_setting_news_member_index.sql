-- 스캔 제안 발송 잡의 대상 리더(KB-614)는 소식을 켠 회원을 member_id 커서로 100건씩 읽는다:
--   WHERE news = 1 AND status = 'ACTIVE' AND member_id > ? ORDER BY member_id LIMIT ?
-- 기존 고유 키 (member_id, installation_id) 로는 news·status 를 확인하려고 행을 하나씩 읽어 걸러야 해서
-- 소식을 켠 회원이 드물수록 스캔 범위가 늘어난다. 동등 조건 두 개를 앞에, 범위·정렬 컬럼을 뒤에 둔 커버링 인덱스로 받친다.
ALTER TABLE notification_setting
    ADD INDEX idx_notification_setting_news_member (news, status, member_id);
