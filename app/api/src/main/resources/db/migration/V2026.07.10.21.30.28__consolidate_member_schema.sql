-- KB-117: member 스키마 재편
--   1) members → member 단수형 리네임
--   2) member_social_identities 의 신원 3컬럼을 member 행으로 흡수 ((provider, provider_uid) 유니크 유지)
--   3) 프로필 4컬럼 → profile JSON 단일 컬럼
--   4) onboarding_status VARCHAR → BOOLEAN
--   5) member_status ENUM 신설 (BaseEntity.status 소프트삭제와 별개 축)
-- 탈퇴 회원은 provider_uid 를 'DELETED:{id}' 삭제 표식으로 두어 유니크 제약을 유지한 채 재가입을 허용한다
-- (상수 'DELETED' 만 쓰면 같은 provider 의 두 번째 탈퇴에서 유니크 충돌이 난다).

RENAME TABLE members TO member;

ALTER TABLE member
    ADD COLUMN provider      ENUM ('GOOGLE','APPLE')      NULL AFTER id,
    ADD COLUMN provider_uid  VARCHAR(255)                 NULL AFTER provider,
    ADD COLUMN email         VARCHAR(255)                 NULL AFTER provider_uid,
    ADD COLUMN profile       JSON                         NULL AFTER nickname,
    ADD COLUMN member_status ENUM ('ACTIVE','SUSPENDED')  NOT NULL DEFAULT 'ACTIVE' AFTER profile;

-- 활성 신원 백필 (구 withdraw 는 신원 행을 hard delete 했으므로 탈퇴 회원엔 매칭 행이 없다)
UPDATE member m
    JOIN member_social_identities si ON si.member_id = m.id
SET m.provider     = si.provider,
    m.provider_uid = si.provider_user_id,
    m.email        = si.email;

-- 신원 행이 없는 과거 탈퇴 회원: 삭제 표식으로 백필해 NOT NULL·유니크 제약과 충돌하지 않게 한다
-- (구 withdraw 는 신원 행을 hard delete 했으므로 원본 provider·email 은 복원할 수 없다)
UPDATE member
SET provider     = 'GOOGLE',
    provider_uid = CONCAT('DELETED:', id)
WHERE provider_uid IS NULL;

-- 프로필 4컬럼 → profile JSON (맵기 미설정 회원은 기본값 5)
UPDATE member
SET profile = JSON_OBJECT(
        'avoidanceSubstanceCodes', COALESCE(avoidance_substance_codes, JSON_ARRAY()),
        'spicinessPreference', COALESCE(spiciness_preference, 5),
        'countryCode', country_code,
        'appLanguage', app_language
              );

-- onboarding_status: VARCHAR('PENDING'|'COMPLETED') → BOOLEAN
UPDATE member
SET onboarding_status = IF(onboarding_status = 'COMPLETED', '1', '0');

ALTER TABLE member
    MODIFY COLUMN provider ENUM ('GOOGLE','APPLE') NOT NULL,
    MODIFY COLUMN provider_uid VARCHAR(255) NOT NULL,
    MODIFY COLUMN profile JSON NOT NULL,
    MODIFY COLUMN onboarding_status TINYINT(1) NOT NULL DEFAULT 0,
    ADD CONSTRAINT uk_member_provider_uid UNIQUE (provider, provider_uid),
    DROP COLUMN avoidance_substance_codes,
    DROP COLUMN spiciness_preference,
    DROP COLUMN country_code,
    DROP COLUMN app_language;

DROP TABLE member_social_identities;
