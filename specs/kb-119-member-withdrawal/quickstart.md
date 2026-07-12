# Quickstart: 회원 탈퇴 (KB-119)

## 건드린 파일 (전체)

**신규 (3)**
- `application/client/.../auth/SocialAccountDeleter.kt` — port `delete(provider, providerUserId)`
- `application/client/.../auth/FirebaseAccountDeleter.kt` — `getUserByProviderUid` → `deleteUser`, `USER_NOT_FOUND` 흡수
- `application/client/.../member/WithdrawUseCase.kt` — `findById` → 소셜 계정 삭제 → `withdraw(id)`

**수정 (5)**
- `application/client/.../auth/AuthConfig.kt` — `socialAccountDeleter` 빈, `UnavailableSocialAuth` 가 두 port 구현
- `application/client/.../auth/AuthErrorCode.kt` — `SOCIAL_ACCOUNT_DELETE_FAILED(500)`
- `application/client/.../auth/RefreshUseCase.kt` — `MemberRepository` 주입 + 재발급 전 회원 존재 확인
- `app/api/.../member/MemberApi.kt` — `@PatchMapping("/me/withdraw")` + swagger(본문 없음)
- `app/api/.../member/MemberController.kt` — `WithdrawUseCase` 주입 + `@AuthMemberId`

**마이그레이션·도메인·`MemberRepository` port·`MemberJpaEntity`·`SocialTokenVerifier`·인증 필터 설정: 변경 없음.**

## 테스트

| 파일 | 성격 | 시나리오 |
|---|---|---|
| `application/client/src/test/.../member/WithdrawUseCaseTest.kt` (신규, 3) | 단위·페이크 | 삭제가 먼저·`withdraw` 가 나중 / 회원 없음 400 / 삭제 실패 500 + `withdraw` 미호출 |
| `application/client/src/test/.../auth/RefreshUseCaseTest.kt` (+1, 총 7) | 단위·페이크 | 탈퇴한 회원(=`findById` null)의 갱신 토큰 → 401 |
| `app/api/src/test/.../member/MemberControllerTest.kt` (+7, 총 22) | Testcontainers+MockMvc | 탈퇴 200(+DB 컬럼) / 프로필 400 / 재탈퇴 400 / 미인증 401 / 삭제 실패 500 + 활성 유지 / 재가입 신규회원·프로필 미승계 / 가입-탈퇴 2회 반복 후 재가입 |
| `app/api/src/test/.../auth/AuthControllerTest.kt` (+1, 총 15) | Testcontainers+MockMvc | 탈퇴 후 기존 refresh token 재발급 → 401 |
| `infra/persistence/src/test/.../member/MemberRepositoryAdapterTest.kt` | Testcontainers | KB-117 그대로(변경 없음) |

**페이크**: `AuthControllerTest.kt` 하단의 `FakeSocialAccountDeleter`(삭제 기록 + `fail()` 스위치)를 `FakeSocialTokenVerifierConfig` 에 `@Primary` 빈으로 추가했다.

## 검증

```bash
./gradlew build
```

수동 확인(SC-001 — 페이크로는 검증 불가): 로컬 docker MySQL + 실제 Firebase 자격증명으로 앱을 띄우고 `scripts/firebase-login-tool.html` 로 로그인 → `PATCH /api/v1/members/me/withdraw` → **Firebase 콘솔 Authentication 사용자 목록에서 계정이 사라졌는지** 눈으로 확인.
