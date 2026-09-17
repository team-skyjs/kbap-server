-- KB-585: 게스트 문의 사진 첨부. 업로드 소유자를 회원으로만 기록하던 것을 기기(설치 ID)로도 기록한다.
--
-- member_id 를 NULL 허용으로 완화하고 installation_id 를 추가한다. 게스트 업로드는 installation_id 만,
-- 회원 업로드는 기존대로 member_id 를 채운다(둘 다 채우는 경로는 두지 않는다 — 소유 판정이 모호해진다).
-- 문의 사진 소유 검증은 "member_id 일치 OR installation_id 일치"라, 게스트로 올린 사진이 가입 후에도
-- 같은 기기에서 통과한다.
--
-- fk_uploaded_image_member 는 그대로 둔다 — MySQL 외래키는 NULL 값을 검사하지 않으므로 게스트 행과 공존한다.
-- 가산 변경만이라 구 코드(항상 member_id 를 채움)는 영향이 없다.
ALTER TABLE `uploaded_image`
    MODIFY COLUMN `member_id` bigint NULL,
    ADD COLUMN `installation_id` varchar(36) NULL,
    ADD INDEX `idx_uploaded_image_installation` (`installation_id`);
