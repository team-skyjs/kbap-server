# Phase 0 Research: 회원 탈퇴 (KB-119)

각 항목은 `Decision / Rationale / Alternatives considered` 로 적는다. **R1·R5·R6 은 구현 중 사용자 결정으로 뒤집혔다**(아래 본문에 반영, 초안 결정은 Alternatives 로 내렸다).

## R1. Firebase 사용자 식별자를 어떻게 확보하는가

**Decision**: 저장된 소셜 신원으로 **서버가 직접 역조회**한다 — `FirebaseAuth.getUserByProviderUid("google.com"|"apple.com", providerUserId).uid` → `deleteUser(uid)`. 클라이언트에서 소셜 ID 토큰을 받지 않는다.

**Rationale**:
- 실제 앱은 **탈퇴 직전 소셜 재로그인을 하지 않는다.** 따라서 "신선한 ID 토큰"이라는 전제 자체가 성립하지 않는다(티켓 Background 와 다름).
- 우리가 이미 저장한 `(provider, provider_uid)` 는 구글/애플이 발급한 안정적 subject 라 유효한 조회 키다. 스키마 변경·클라이언트 변경이 모두 0이다.
- Firebase local uid 는 저장하지 않는다. 요청마다 역조회 1회를 더 하지만 탈퇴는 저빈도 경로다.

**Alternatives considered**:
- 클라이언트가 신선한 ID 토큰을 본문으로 보내 uid 를 꺼내고 동시에 재인증(초안·티켓안) — 앱이 재로그인을 하지 않으므로 불가. **기각**.
- `firebase_uid` 컬럼 신설 — 마이그레이션 + 기존 회원 백필이 필요하다. 역조회 한 번이 더 싸다. 기각.

**대가(수용)**: 재인증이 사라져 **접근 토큰이 탈취되면 계정 삭제가 가능**하다(access TTL 30분). 되돌릴 수 없는 작업에 재인증이 필요해지면 ID 토큰 방식을 후속으로 되살린다.

## R2. Firebase 사용자 기록 삭제 포트

**Decision**: 포트 `SocialAccountDeleter { fun delete(provider: SocialProvider, providerUserId: String) }` 를 `:application:client` auth 패키지에 두고, `FirebaseAccountDeleter(firebaseApp)` 가 역조회 + `deleteUser` 로 구현한다. 자격증명이 없을 때 쓰던 `UnavailableSocialTokenVerifier` 는 **두 포트를 모두 구현**하도록 바꿨다(`UnavailableSocialAuth`) — 부팅은 되고 호출만 거절되는 현행 동작 유지.

**Rationale**: "토큰 검증기"에 삭제를 얹으면 이름이 거짓말이 되고 `LoginUseCase` 가 필요 없는 삭제 능력에 묶인다. 포트가 **도메인 타입**(`SocialProvider`)만 노출하므로 유스케이스는 Firebase 를 전혀 모른다.

**이미 삭제된 사용자**: `getUserByProviderUid` 는 대상이 없으면 `FirebaseAuthException(USER_NOT_FOUND)` 를 던진다. 이 경우만 **성공으로 흡수**(멱등)하고 나머지 실패는 위로 던진다.

**Alternatives considered**: 포트 시그니처를 `delete(authUserId)` 로 두고 uid 를 상위에서 넘김 — uid 를 아는 주체가 어댑터뿐이라 계층이 꼬인다. 기각.

## R3. Firebase 삭제 실패 시 처리 순서

**Decision**: `WithdrawUseCase` 는 **Firebase 삭제를 먼저** 하고 성공한 뒤에야 DB 탈퇴를 호출한다. 삭제 실패 시 `AuthErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED`(500)를 던지고 DB 는 손대지 않으며, `memberId`·`provider`·`providerUserId`·사유를 담은 **ERROR 로그**를 남긴다.

**Rationale**:
- DB 를 먼저 지우면 `provider_uid` 가 `DELETED:{id}` 로 덮여, 인증 제공자에서 그 계정을 찾을 **조회 키 자체를 잃는다**(이 설계에선 조회 키가 곧 provider_uid 라 치명적).
- 역순(Firebase 성공 → DB 실패)의 잔여 상태는 안전하다: Firebase 사용자 기록이 지워져도 같은 구글/애플 계정으로 다시 로그인하면 provider subject 가 동일하므로 `findByIdentity` 가 기존 회원을 찾아 정상 로그인된다. 회원은 탈퇴를 재시도하면 되고, 재시도 시 Firebase 삭제는 멱등하게 통과한다.

**트랜잭션**: `WithdrawUseCase` 에 `@Transactional` 을 붙이지 않는다(헌법 "외부 호출을 DB 트랜잭션 안에서 길게 잡지 않는다"). DB 쓰기는 `memberRepository.withdraw(id)` 단일 호출이라 별도 경계가 불필요하다.

## R4. 탈퇴 회원의 갱신 토큰 무효화

**Decision**: `RefreshUseCase` 가 `refreshTokenStore.consume(jti)` 로 얻은 `memberId` 에 대해 **`memberRepository.findById(memberId)` 로 회원 유효성을 확인**하고, 없으면 `INVALID_REFRESH_TOKEN`(401)을 던진다. Redis 에서 회원의 토큰을 열거해 지우지 않는다.

**Rationale**:
- `RefreshTokenStore` 는 `save/consume/delete(jti)` 뿐이라 **회원별 색인이 없다**. 색인을 새로 두면 Redis 키 구조·정리 로직·인터페이스가 모두 늘어난다.
- 존재 확인 한 줄이면 **모든 기기의 잔여 갱신 토큰이 동시에 무력화**된다(FR-005). `consume` 이 회전(getAndDelete)이라 사용된 토큰은 그 자리에서 소멸하고, 미사용 잔여 키는 TTL 이 정리한다.

**Alternatives considered**: `auth:refresh:member:{id}` SET 색인 추가 후 탈퇴 시 일괄 삭제 — 티켓 DoD 문구("Redis 에서 제거")에 가깝지만 구조 변경 비용이 크고 색인 누락 시 구멍이 생긴다. 기각.

## R5. 소프트 삭제 시 지우는 컬럼

**Decision**: `provider_uid` 만 `DELETED:{id}` 로 치환하고 **이메일·닉네임·프로필은 그대로 남긴다**. 마이그레이션은 없다.

**Rationale**:
- `provider_uid` 치환은 선택이 아니라 **필수**다. 소프트 삭제라 유니크 인덱스 `uk_member_provider_uid(provider, provider_uid)` 에 항목이 남으므로, 치환하지 않으면 같은 소셜 계정 재가입 INSERT 가 유니크 제약에 걸려 409 가 된다(조회를 ACTIVE 로 필터해도 INSERT 는 막힌다).
- 회원 PK 접미사는 회원마다 유일해 재가입·재탈퇴를 반복해도 충돌하지 않는다. 시각·UUID 를 섞는 새 스킴은 불필요하다.
- 이메일은 우리 DB 에 남긴다(사용자 결정). 인증 제공자 쪽 이메일은 계정 삭제로 사라지므로 티켓의 핵심 목적은 달성된다.

**Alternatives considered**:
- 초안: `email = null` 도 함께 — 이메일에는 인덱스가 없어 비용은 0이지만, 유지하기로 결정. 기각.
- `provider_uid` 도 그대로 두고 유니크 제약을 일반 인덱스로 낮추기 — 동시 최초 로그인 경합에서 회원 중복 생성을 막던 DB 수단(`LoginUseCase` 의 `DUPLICATE_SOCIAL_IDENTITY` 폴백)이 사라진다. 기각.

## R6. 오류 코드

**Decision**: `AuthErrorCode` 에 `SOCIAL_ACCOUNT_DELETE_FAILED(500, "소셜 계정 삭제에 실패했습니다. 잠시 후 다시 시도해 주세요")` 하나만 추가한다.

**Rationale**: `GlobalExceptionHandler` 가 `ErrorCode.status` 를 HTTP 상태로 그대로 쓰므로 별도 매핑이 필요 없고, `ErrorCodeStatusTest`(ArchUnit) 의 4xx/5xx 제약도 만족한다.

**Alternatives considered**: `SOCIAL_IDENTITY_MISMATCH(403)` — ID 토큰 재인증이 사라지면서 **대조할 신원 자체가 없어졌다**. `@AuthMemberId` 가 대상 회원을 접근 토큰에서 뽑으므로 남의 계정을 지정할 경로가 구조적으로 없다. 기각(삭제).

## R7. 엔드포인트

**Decision**: `PATCH /api/v1/auth/withdraw`, **요청 본문 없음**, 응답 `BaseResponse<Unit>`. `@AuthMemberId` 로 회원을 해석하며 `AuthApi`/`AuthController` 에 둔다.

**Rationale**: 탈퇴는 세션·소셜 계정의 종료라 **인증 API 그룹**에 묶는 편이 클라이언트가 찾기 쉽다(로그인·재발급·로그아웃과 한 태그). 애너테이션은 컨벤션대로 인터페이스가 아니라 컨트롤러 구현 파라미터에 단다.

**주의(함정)**: `/api/v1/auth/*` 는 로그인·재발급·로그아웃이 공개라 **인증 필터 밖**이다. 따라서 `WebMvcAuthConfig` 의 `addUrlPatterns` 에 **`/api/v1/auth/withdraw` 정확 경로만** 추가해야 한다 — 와일드카드 `/auth/*` 를 넣으면 로그인이 401 로 막힌다.

## R8. Sign in with Apple 토큰 revoke

**Decision**: 이번 구현 범위 밖. 의무 범위(애플 심사지침이 revoke 까지 요구하는지)와 구현 방법(`appleid.apple.com/auth/revoke`, client_secret JWT 필요 여부, Firebase 대행 여부)을 조사해 **별도 Jira 태스크**로 남긴다.

**Rationale**: 조사 결과에 따라 애플 개발자 키 발급·서명 로직이 필요할 수 있어 이번 PR 에 묶으면 범위가 부푼다.
