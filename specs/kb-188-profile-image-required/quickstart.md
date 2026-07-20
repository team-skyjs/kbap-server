# Quickstart: 프로필 사진 필수화 (KB-188) 검증 런북

## 1. 테스트 실행

```bash
./gradlew :domain:member:test --tests "com.kbap.domain.member.model.MemberProfileTest"
./gradlew :app:api:test --tests "com.kbap.app.api.member.MemberControllerTest"
./gradlew build          # 전체 회귀 (시나리오 테스트 포함)
```

## 2. 수동 API 검증 (local 부팅 후)

```bash
./gradlew :app:api:bootRun    # SPRING_PROFILES_ACTIVE=local
TOKEN=<온보딩 전 회원 access token>

# 미전송 → 400 COMMON-002
curl -s -X POST localhost:8080/api/v1/members/me/onboarding \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"nickname":"길동","avoidanceSubstanceCodes":[],"countryCode":"US","appLanguage":"en","spicinessPreference":-1}'

# 빈 문자열 → 400 MEMBER-008
curl -s -X POST localhost:8080/api/v1/members/me/onboarding \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"nickname":"길동","avoidanceSubstanceCodes":[],"countryCode":"US","appLanguage":"en","spicinessPreference":-1,"profileImageUrl":""}'

# 기본 이미지 경로 → 200, 조회 시 CDN 조합 URL
curl -s -X POST localhost:8080/api/v1/members/me/onboarding \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"nickname":"길동","avoidanceSubstanceCodes":[],"countryCode":"US","appLanguage":"en","spicinessPreference":-1,"profileImageUrl":"/images/default/profile/profile-default-512.png"}'
curl -s localhost:8080/api/v1/members/me/profile -H "Authorization: Bearer $TOKEN"

# 수정 빈 문자열 → 400 MEMBER-008 / 미전송 → 유지
curl -s -X PATCH localhost:8080/api/v1/members/me/profile \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"profileImageUrl":""}'
```

## 3. 백필 마이그레이션 검증 (배포 후, 환경별 1회)

```sql
-- 배포 전 대상 행 수 파악
SELECT COUNT(*) FROM member
WHERE JSON_UNQUOTE(JSON_EXTRACT(profile, '$.profileImageUrl')) IS NULL;

-- 배포(Flyway 적용) 후 0 이어야 함
SELECT COUNT(*) FROM member
WHERE JSON_UNQUOTE(JSON_EXTRACT(profile, '$.profileImageUrl')) IS NULL;

-- flyway 이력 확인
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
```

주의: 배포 직전 사이에 신규 가입(온보딩 전) 행이 생기면 그 행도 백필 대상으로 채워진다 — 문제 없음(온보딩 시 어차피 값 필수). 배포 후 신규 가입~온보딩 전 행의 null 은 정상 상태다.

## 4. Swagger 확인

- `/swagger-ui` 에서 온보딩 요청의 `profileImageUrl` 이 필수로 표기되고 기본 이미지 경로 계약이 서술되는지, 수정 API 문구가 2분법(빈 문자열=400)으로 바뀌었는지 확인.
