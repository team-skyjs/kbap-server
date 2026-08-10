# Quickstart: 온보딩 랜덤 프로필 (KB-300)

## 수동 확인

로컬 실행:

```bash
./gradlew :api:bootRun     # SPRING_PROFILES_ACTIVE=local
```

1. 로그인해 access token 확보 (`POST /api/v1/auth/login`).
2. **온보딩** — `X-API-Version: 2` 헤더와 함께 닉네임·사진 없이:

```bash
curl -X POST http://localhost:8080/api/v1/members/me/onboarding \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'X-API-Version: 2' \
  -d '{"avoidanceSubstanceCodes":["EGG"],"countryCode":"US","spicinessPreference":"SKIP"}'
```

3. 프로필 조회에서 닉네임·사진이 자동으로 채워졌는지 확인:

```bash
curl http://localhost:8080/api/v1/members/me/profile -H "Authorization: Bearer $TOKEN"
# payload.nickname        → 영숫자 6자 코드 (예: K7M2XB)
# payload.profileImageUrl → 공개 베이스 URL + images/webp/default_profile/avatar-{amber|navy|olive|orange|plum|teal}.png
```

4. **기존 형식 회귀** — 다른 계정으로, 헤더 없이 닉네임·사진을 보내는 기존 요청이 그대로 동작하는지:

```bash
curl -X POST http://localhost:8080/api/v1/members/me/onboarding \
  -H "Authorization: Bearer $TOKEN2" -H 'Content-Type: application/json' \
  -d '{"nickname":"길동이","avoidanceSubstanceCodes":[],"countryCode":"US","spicinessPreference":"SKIP","profileImageUrl":"images/default/profile/profile-default-512.png"}'
# → 200, 조회 시 nickname == "길동이" (랜덤으로 덮이지 않음)
```

Swagger UI: `http://localhost:8080/swagger-ui.html` — "회원" 태그의 온보딩에서 `X-API-Version` 헤더 파라미터와 분기 동작이 안내된다.

## 테스트

```bash
./gradlew :common:test --tests "*OnboardingProfileDefaultsTest"   # 후보 상수 유효성·분포 (단위, 빠름)
./gradlew :api:test --tests "*MemberControllerTest"               # 온보딩 자동 지정 + 기존 시나리오 회귀 (Testcontainers)
./gradlew build                                                    # 전체
```

## 롤백

DB 스키마 변경이 없으므로 서버 리비전 롤백만으로 되돌아간다. 롤백 후 자동 지정으로 온보딩한 회원은 이미 지정된 닉네임·사진을 그대로 유지하며(일반 프로필 값과 구분되지 않음), 닉네임·사진을 보내던 기존 요청은 전 구간 영향이 없다.
