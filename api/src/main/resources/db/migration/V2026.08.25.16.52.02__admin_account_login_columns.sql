-- KB-375: 관리자 계정 관리 — 마지막 로그인·비밀번호 변경 시각. NULL 허용이라 리비전 공존 안전.
ALTER TABLE `admin_account`
    ADD COLUMN `last_login_at` datetime(6) DEFAULT NULL AFTER `admin_pwd`,
    ADD COLUMN `password_changed_at` datetime(6) DEFAULT NULL AFTER `last_login_at`;
