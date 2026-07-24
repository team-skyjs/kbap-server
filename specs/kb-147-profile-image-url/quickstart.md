# Quickstart: 프로필 사진 URL·맵기 선호 (KB-147)

## 테스트 실행

```bash
./gradlew :domain:member:test --tests "com.kbap.domain.member.MemberServiceTest"   # 도메인 통합 (Testcontainers)
./gradlew :app:api:test --tests "com.kbap.app.api.member.MemberControllerTest"     # MockMvc 통합
./gradlew build                                                                     # 전체 회귀
```

## 수동 검증 (local — 허용 호스트 미설정 = 형식 검증만)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
TOKEN="<액세스 토큰>"

# 1) 온보딩에 사진·맵기 포함
curl -X POST localhost:8080/api/v1/members/me/onboarding -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"nickname":"홍길동","countryCode":"US","appLanguage":"en","profileImageUrl":"https://example.com/p.jpg","spicinessPreference":7}'

# 2) 조회에 profileImageUrl·spicinessPreference 확인
curl localhost:8080/api/v1/members/me/profile -H "Authorization: Bearer $TOKEN"

# 3) 교체 → 유지 → 제거
curl -X PATCH localhost:8080/api/v1/members/me/profile -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"profileImageUrl":"https://example.com/new.jpg"}'
curl -X PATCH localhost:8080/api/v1/members/me/profile -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"nickname":"새닉"}'   # 사진 유지
curl -X PATCH localhost:8080/api/v1/members/me/profile -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"profileImageUrl":""}' # 사진 제거

# 4) 맵기 변경 → 조회로 확인
curl -X PATCH localhost:8080/api/v1/members/me/profile -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"spicinessPreference":9}'

# 5) 불합격 URL → 400 MEMBER-008, 범위 밖 맵기 → 400 MEMBER-009
curl -X PATCH localhost:8080/api/v1/members/me/profile -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"profileImageUrl":"http://insecure.com/p.jpg"}'
curl -X PATCH localhost:8080/api/v1/members/me/profile -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"spicinessPreference":11}'
```

## 허용 호스트 제한 확인 (prod 설정 시뮬레이션)

`application-local.yml` 에 임시로 아래를 넣고 재기동 → CDN 밖 URL 이 400 으로 거절되는지 확인:

```yaml
kbap:
  member:
    profile-image-allowed-hosts: dxxxx.cloudfront.net
```
