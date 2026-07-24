# Quickstart: KB-191 provider 필드 검증

## 1. 테스트로 검증 (기본)

```bash
./gradlew :app:api:test --tests "com.kbap.app.api.member.MemberControllerTest"
```

- Red 단계: provider assertion 추가 직후 실패 확인.
- Green 단계: DTO 2파일 매핑 후 전체 통과.

## 2. 로컬 수동 확인 (선택)

```bash
./gradlew :app:api:bootRun   # SPRING_PROFILES_ACTIVE=local
# 로그인 후:
curl -H "Authorization: Bearer <accessToken>" http://localhost:8080/api/v1/members/me/profile
# payload.provider == "GOOGLE" | "APPLE" 확인
```

## 3. Swagger 확인

- `/swagger-ui.html` → Member → `GET /api/v1/members/me/profile` 예시 응답에 `provider` 노출 확인.
