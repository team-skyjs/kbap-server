# Tasks: 회원 탈퇴 — DB 소프트 삭제와 Firebase user record 삭제

**Input**: Design documents from `/specs/kb-119-member-withdrawal/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/withdraw-api.md](./contracts/withdraw-api.md)

**Tests**: Test-First 는 NON-NEGOTIABLE(헌법 원칙 I). 각 스토리는 구현 전에 실패하는 테스트를 먼저 쓰고 Red 를 확인했다.

> **구현 중 설계 변경 2건** (사용자 확정 — spec.md 상단 참조):
> 1. **소셜 ID 토큰 재인증 폐기.** 앱이 탈퇴 직전 재로그인을 하지 않으므로 서버가 저장된 `(provider, providerUserId)` 로 인증 제공자에 역조회해 삭제한다. 요청 본문이 사라졌고, **US3(남의 계정 대신 탈퇴 금지)은 구조적으로 불가능해져 삭제**했다(`@AuthMemberId` 가 대상 회원을 접근 토큰에서 뽑으므로 타인을 지정할 경로가 없다).
> 2. **이메일 유지.** `provider_uid` 만 더미 치환한다(유니크 제약 때문에 필수). 영속 코드가 KB-117 그대로라 `MemberJpaEntity` 변경도 사라졌다.

## Phase 1: Setup

- [X] T001 `./gradlew build` 로 기준선 green 확인.

---

## Phase 2: Foundational

- [X] T002 ~~`SocialTokenVerifier` 반환 타입 확장~~ — **설계 변경으로 철회**. `verify(idToken): SocialIdentity` 원상 복구(Firebase local uid 를 토큰에서 꺼낼 필요가 없어짐).
- [X] T003 `application/client/.../auth/SocialAccountDeleter.kt`(port `delete(provider, providerUserId)`) + `application/client/.../auth/FirebaseAccountDeleter.kt`(`getUserByProviderUid("google.com"|"apple.com", providerUserId).uid` → `deleteUser(uid)`, `USER_NOT_FOUND` 는 성공 흡수) 신규. `AuthConfig` 에 `socialAccountDeleter` 빈 추가 + `UnavailableSocialTokenVerifier` → `UnavailableSocialAuth`(두 port 구현).
- [X] T004 `app/api/src/test/.../auth/AuthControllerTest.kt` 에 `FakeSocialAccountDeleter`(삭제 기록 + `fail()` 스위치) 추가하고 `FakeSocialTokenVerifierConfig` 에 `@Primary` 빈으로 등록.

---

## Phase 3: User Story 1 — 탈퇴 시 DB·인증 제공자 양쪽 기록 삭제 (P1) 🎯 MVP

- [X] T005 [US1] `application/client/src/test/.../member/WithdrawUseCaseTest.kt` 신규(Red 확인: `Unresolved reference 'WithdrawUseCase'`) — 삭제가 먼저·`withdraw` 가 나중 / 회원 없음 `MEMBER_NOT_FOUND` / 삭제 실패 시 `SOCIAL_ACCOUNT_DELETE_FAILED` 이고 `withdraw` 미호출.
- [X] T006 ~~영속 테스트의 이메일 기대 뒤집기~~ — **설계 변경으로 철회**(이메일 유지). `MemberRepositoryAdapterTest` 는 KB-117 그대로.
- [X] T007 [US1] `application/client/.../auth/AuthErrorCode.kt` 에 `SOCIAL_ACCOUNT_DELETE_FAILED(500)` 추가.
- [X] T008 [US1] `application/client/.../member/WithdrawUseCase.kt` — `findById` → 소셜 계정 삭제(실패 시 ERROR 로그 + 500) → `memberRepository.withdraw`. `@Transactional` 없음.
- [X] T009 ~~`MemberJpaEntity.withdraw()` 에 `email = null`~~ — **설계 변경으로 철회**.
- [X] T010 [US1] `app/api/src/test/.../member/MemberControllerTest.kt` 에 탈퇴 시나리오 5건 추가(Red) — 200 + DB 컬럼(`provider_uid`·`status`) / 프로필 400 / 재탈퇴 400 / 미인증 401 / 삭제 실패 500 + 활성 유지.
- [X] T011 [US1] `app/api/.../member/MemberApi.kt`(`@PatchMapping("/withdraw")`, 본문 없음, swagger 200/400/401/500) + `MemberController` 에 `WithdrawUseCase` 주입·`@AuthMemberId` 배선. green 확인.
- [X] T012 [US1] `app/api/src/test/.../auth/AuthControllerTest.kt` 에 "탈퇴한 회원의 refresh 토큰 → 401" 추가 + `RefreshUseCaseTest` 에 단위 시나리오 추가(Red).
- [X] T013 [US1] `application/client/.../auth/RefreshUseCase.kt` 에 `MemberRepository` 주입 + `consume` 후 회원 존재 확인 → 없으면 `INVALID_REFRESH_TOKEN`. green 확인.

---

## Phase 4: User Story 2 — 같은 소셜 계정 재가입 (P2)

- [X] T014 [US2] `MemberControllerTest` 에 재가입 시나리오 2건 추가 — 탈퇴 후 재로그인 시 `newMember=true`·새 id·프로필 미승계 / 가입-탈퇴 2회 반복 후 재가입 성공.
- [X] T015 [US2] green 확인 — **예상대로 프로덕션 코드 변경 없이 통과**(`DELETED:{id}` 더미 + `findByIdentity` 의 ACTIVE 필터가 이미 처리).

---

## Phase 5: ~~User Story 3 — 남의 계정 대신 탈퇴 금지~~ (삭제)

소셜 ID 토큰 재인증이 사라지면서 **대조할 신원 자체가 없어졌다.** `@AuthMemberId` 가 접근 토큰에서 대상 회원을 뽑으므로 타인의 계정을 지정할 경로가 구조적으로 존재하지 않는다. `SOCIAL_IDENTITY_MISMATCH(403)` 오류 코드도 함께 폐기했다.

**대가**: 재인증이 없으므로 **접근 토큰이 탈취되면 계정 삭제가 가능**하다(access TTL 30분). spec.md Assumptions 에 기록.

---

## Phase 6: Polish & Cross-Cutting

- [X] T019 `./gradlew build` 전체 green — ArchUnit(`ErrorCodeStatusTest` 신규 500 코드, `ModuleBoundaryTest`) 포함.
- [ ] T020 [P] Swagger UI 에서 `PATCH /api/v1/auth/withdraw` 문서가 [contracts/withdraw-api.md](./contracts/withdraw-api.md) 와 일치하는지 확인(본문 없음·200/400/401/500).
- [ ] T021 [P] 로컬 docker + 실제 Firebase 자격증명으로 로그인 → 탈퇴 → **Firebase 콘솔에서 계정 소멸 확인**(SC-001 — 페이크로 검증 불가한 유일 항목이자, `getUserByProviderUid` 역조회가 실제로 동작하는지 확인하는 유일한 경로).
- [X] T022 [P] Sign in with Apple 토큰 revoke 조사 완료 → **KB-122** 등록. 결론: revoke 엔드포인트는 애플이 발급한 refresh/access token 을 보유해야 호출 가능한데, Firebase 가 Sign in with Apple 을 대행해 **우리는 애플 토큰을 전혀 받지 못한다**(`FirebaseAuth.deleteUser()` 도 애플 연결을 끊지 않는다). 즉 **KB-119 만으로는 애플 심사 요건(5.1.1(v)) 미충족**이며, 애플 로그인 출시 전에 (A) 클라이언트에서 `revokeToken(withAuthorizationCode:)` 호출(탈퇴 시 애플 재인증 필요) 또는 (B) 로그인 때 받은 `authorizationCode` 를 서버가 애플 refresh token 으로 교환·저장했다가 탈퇴 시 revoke(ES256 client_secret JWT + 토큰 at-rest 보관) 중 하나를 구현해야 한다.

---

## 총계

**17개 태스크** (원래 22개 중 US3 3건 + 철회 T002/T006/T009 반영) — 완료 13 · 남은 3(T020~T022, 모두 코드 외 검증·후속).
