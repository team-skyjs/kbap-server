# Research: 푸시 토큰 등록 API 와 기기-회원 연결

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 설계 갈림길에서 내린 결정과 근거다. KB-464 의 R2(upsert 동시성 감수)·R3(lang 그대로 저장)·R4(동의 원장·승계 규칙)·R7(기기 행 삭제 금지)은 전제로 계승한다.

## R1. 기능 패키지·클래스 이름 — `api.notification` / `NotificationToken*` (2026-09-07 사용자 결정)

- **Decision**: `com.kbap.api.notification` 에 `NotificationTokenController`·`NotificationTokenApi`·`NotificationTokenRegisterRequest`·`NotificationTokenService` 를 둔다. 접두는 도메인 컨텍스트명(`notification`)이고 `push` 는 쓰지 않는다. **URL 도 `/api/notifications/tokens`** 로 둔다(사용자 결정 — Jira 초안 `/api/push/tokens` 대체). 복수형은 기존 리소스 규약(`/api/scans`·`/api/reviews`·`/api/orders`)을 따르며, KB-466 설정(`/api/notifications/settings`)·KB-467 알림함(`/api/notifications`)이 같은 베이스 아래 붙는다. Jira KB-465 본문과 FE 공유가 함께 갱신돼야 한다(tasks T018).
- **Rationale**: 소비하는 도메인이 `common.domain.notification` 이라 패키지·클래스가 같은 이름을 쓰면 컨텍스트가 한눈에 잡힌다. 에픽의 후속 API(KB-466 설정·KB-467 알림함)도 같은 컨텍스트라 한 패키지에 쌓인다.
- **Alternatives considered**: `api.push` + `PushToken*` + `/api/push/tokens`(Jira 초안) — 도메인명과 어긋나고 후속 알림 API 와 베이스가 갈려 기각. `api.notification.token` 하위 패키지 — 파일 4개에 하위 패키지는 규약 위반(소규모 기능에 하위 패키지 금지).

## R2. 인증 방식 — JWT 필터 미등록 + `@AuthMemberIdOrNull`

- **Decision**: `PUT /api/notifications/tokens` 는 `WebConfig.jwtAuthenticationFilterRegistration` 의 `addUrlPatterns` 에 **등록하지 않고**, 컨트롤러 파라미터 `@AuthMemberIdOrNull memberId: Long?` 로 회원 여부를 판정한다.
- **Rationale**: 게스트·회원 겸용 엔드포인트의 기존 선례가 `/api/home` 이며 정확히 이 구조다. `JwtAuthenticationFilter` 는 토큰 없으면 401 을 쓰는 필터라 게스트 허용 경로에 등록하면 `GuestExemption` 으로 필터 전체를 건너뛰게 되는데, 그러면 등록 자체가 무의미하다. `AuthMemberIdOrNullArgumentResolver` 는 `Authorization` 헤더가 있으면 직접 파싱하므로 위조·만료 토큰은 `BusinessException` → 401 로 여전히 거절되고, 헤더가 없으면 게스트(null)다. Jira 본문의 "보호 경로 등록 + 게스트 예외" 는 결과적으로 같은 의미이며 코드 선례에 맞춰 이렇게 읽는다.
- **Alternatives considered**: 필터 등록 + `GuestExemption("PUT", /api/notifications/tokens)` — 필터가 항상 건너뛰어 등록이 죽은 코드가 된다.

## R3. 기기 식별자 헤더 — 상수 위치와 검증

- **Decision**: 헤더명 `X-Installation-Id` 상수는 `com.kbap.api.core.ApiHeaders.INSTALLATION_ID` 한 곳에 둔다. 등록 API 에서는 `@RequestHeader(ApiHeaders.INSTALLATION_ID) @NotBlank @Size(max = 36) installationId: String`(필수), 로그인·로그아웃에서는 `@RequestHeader(ApiHeaders.INSTALLATION_ID, required = false) installationId: String?`(선택).
- **Rationale**: 소비자가 두 기능 패키지(notification·auth)라 어느 한쪽 컨트롤러 상수를 다른 쪽이 참조하면 방향이 어색하다. `ApiPaths` 와 같은 자리(`api.core`)의 3줄짜리 object 가 가장 싸다. 헤더 누락은 Spring 의 `MissingRequestHeaderException`(`ErrorResponse` 구현체)이 `GlobalExceptionHandler.handleUnexpected` 의 `ErrorResponse` 분기로 **400 COMMON-002** 가 되므로 새 ErrorCode 가 필요 없다. 빈 문자열·36자 초과는 메서드 파라미터 제약(`HandlerMethodValidationException` 핸들러 기존)이 400 으로 막아 DB 컬럼 길이(36) 위반 500 을 방지한다. 로그인·로그아웃은 헤더가 선택이라 없으면 기기 처리만 생략한다(FR-013).
- **Alternatives considered**: 전용 ErrorCode `PUSH-001`(헤더 누락) — 클라이언트가 분기할 이유가 없는 단순 요청 오류라 COMMON-002 로 충분. 인터셉터·필터로 헤더 강제 — 소비 엔드포인트가 하나뿐이라 과하다.

## R4. 서비스 경계 — `NotificationTokenService` 가 정책·트랜잭션 소유, `AuthService` 는 순서 조합

- **Decision**: `api.notification.NotificationTokenService`(`@Service`)에 `@Transactional` 메서드 4개를 둔다: `registerToken(installationId, memberId?, token, platform, lang, marketing?)`·`linkOnLogin(installationId, memberId)`·`unlinkOnLogout(installationId)`·`closeOnWithdraw(memberId)`. `AuthService.login(idToken, installationId = null)`·`logout(refreshToken, installationId = null)`·`withdraw(memberId, releaseDevices = false)` 가 인자가 주어졌을 때만 이를 호출한다 — 기본값 경로가 곧 종전 1.0 동작이라 1.0 컨트롤러 호출부는 한 글자도 바뀌지 않는다(R11). `AuthService` 자체는 종전대로 트랜잭션이 없다.
- **Rationale**: 동의 원장 정책(같은 버전 무변화·다른 버전 교체·인수/철회 분기)은 도메인 로직이므로 서비스가 소유해야 하고(헌법 IV), 그 소비자가 토큰 컨트롤러와 auth 서비스 둘이라 한 서비스에 모은다. `AuthService.login` 은 `findOrSignUp` 이 단일 트랜잭션이 될 수 없는 구조(unique 위반 폴백)라 트랜잭션을 걸 수 없고, `withdraw` 는 외부 소셜 삭제를 먼저 하므로 마찬가지다. 따라서 기기 처리는 자기 트랜잭션을 갖는 별도 메서드여야 한다.
- **Alternatives considered**: `MemberService.withdraw` 가 기기 정리를 호출 — member → notification 방향의 기능 간 의존이 생기고 회원 서비스가 알림 정책을 알게 된다. `AuthService` 메서드에 `@Transactional` — 외부 호출을 트랜잭션 안에 넣는 규약 위반.

## R5. 탈퇴 순서 — 기기·동의 정리를 회원 탈퇴 마킹보다 먼저

- **Decision**: `AuthService.withdraw(memberId, releaseDevices)` 순서는 소셜 삭제 → (`releaseDevices` 일 때) `notificationTokenService.closeOnWithdraw(memberId)` → `memberService.withdraw(memberId)`.
- **Rationale**: 두 DB 작업이 별도 트랜잭션이라 중간 실패 시 한쪽만 반영될 수 있다. 기기 정리가 먼저 성공하고 회원 마킹이 실패하면 회원은 활성인 채 기기 연결만 풀린 상태가 되는데, 다음 로그인이 다시 연결하므로 자기 복구된다. 반대 순서에서 회원 마킹 뒤 기기 정리가 실패하면 탈퇴 회원의 기기가 발송 대상으로 남는다 — spec 이 막으려는 바로 그 상태다. 단일 트랜잭션으로 묶으려면 notification↔member 서비스 간 호출 방향이 생기므로 순서로 푼다(동시성·부분 실패 감수 규약).
- **Alternatives considered**: 두 작업을 묶는 `@Transactional` 조합 메서드를 `AuthService` 에 추가 — 자기 호출은 프록시를 타지 않아 별도 빈이 필요, 파일 하나가 늘어난다. 실패 확률(같은 DB, 연속 두 문장)에 비해 과하다.

## R6. 로그인 시 기기 연결과 동의 인수의 독립 처리

- **Decision**: `linkOnLogin` 은 (1) `findByInstallationId` 가 있으면 `linkMember`, 없으면 건너뛰고 (2) `findOpenGuestByInstallationId` 가 비어 있지 않으면 `findOpenByMemberId` 가 비었을 때 전부 `claim(memberId)`, 아니면 전부 `revoke(now)` 한다. (1)·(2)는 서로 조건이 아니다. 어느 쪽도 행을 새로 만들지 않는다.
- **Rationale**: FR-009·FR-010 이 각각 독립 요구다. 정상 경로에서 게스트 동의는 등록 API 로만 생기므로 기기 행 없이 동의만 있는 상태는 만들어지지 않지만, 한쪽을 다른 쪽의 조건으로 걸면 데이터 정리 등으로 어긋난 경우 동의가 영구히 게스트로 남는다. 목록 전체 처리는 KB-464 R4 의 "게스트 조회는 목록" 결정(중복 행 방어)을 그대로 따른다.
- **Alternatives considered**: 기기 행이 있을 때만 동의 처리 — 위 잔존 문제.

## R7. 게스트 동의 on 의 버전 비교 규칙

- **Decision**: `marketing=true, version=v` 일 때 열린 게스트 행 목록에서 `consentVersion != v` 인 행은 전부 `revoke(now)`, `== v` 인 행이 하나라도 있으면 삽입하지 않고, 없으면 `grantForInstallation(installationId, v, now)` 를 `save`. `marketing=false` 면 열린 게스트 행 전부 `revoke(now)`. `settings` 자체가 없으면 원장을 건드리지 않는다.
- **Rationale**: spec US2 의 5개 시나리오를 문자 그대로 구현한 것이고, 조회 1회(`findOpenGuestByInstallationId`)로 끝난다. 같은 버전 중복 행이 이미 있어도 "변화 없음" 이라 중복을 늘리지 않는다. 최신 버전 검증은 하지 않는다(spec 가정 — KB-466 이후).
- **Alternatives considered**: 버전 대소 비교(낮은 버전 요청은 무시) — spec 이 "다른 버전" 만 정의했고 앱이 서버 정책 없이 보내는 값이라 대소 의미가 없다.

## R8. 플랫폼 값의 바인딩

- **Decision**: 요청 DTO 의 `platform` 은 `String?` + `@field:NotBlank` + `@field:Pattern(regexp = "(?i)ios|android")`. 컨트롤러가 `DevicePlatform.valueOf(platform.uppercase())` 로 확정값을 서비스에 넘긴다.
- **Rationale**: 앱(Expo)의 `Platform.OS` 는 소문자 `ios`/`android` 를 준다. enum 을 DTO 에 직접 바인딩하면 대소문자 불일치가 JSON 역직렬화 실패(400 이지만 메시지가 "본문을 해석할 수 없습니다")로 나와 원인이 흐리다. 검증은 요청 경계가 소유(헌법 V)하고, 변환은 `ScanV2Controller.requestedCurrency` 선례처럼 컨트롤러가 한다.
- **Alternatives considered**: Jackson `ACCEPT_CASE_INSENSITIVE_ENUMS` 전역 설정 — 이 엔드포인트 하나 때문에 전 API 의 enum 바인딩 의미를 바꾼다.

## R9. 동의 on 이면 버전 필수 — DTO `@get:AssertTrue`

- **Decision**: `settings` 객체에 `@get:AssertTrue(message = "marketing 이 true 면 marketingConsentVersion 이 필요합니다") val versionPresentWhenOptedIn: Boolean get() = marketing != true || marketingConsentVersion != null` 을 두고, `marketingConsentVersion` 에 `@field:Positive`. 요청 루트는 `@field:Valid val settings: ...?` 로 캐스케이드.
- **Rationale**: 조건부 필수는 Bean Validation 표준 애너테이션으로 표현할 수 없어 `OrderCreateRequest` 가 같은 방식(`@get:AssertTrue`)을 이미 쓴다. `MethodArgumentNotValidException` 핸들러가 필드명·메시지를 400 으로 내려 앱이 원인을 바로 본다.
- **Alternatives considered**: 서비스에서 `require` — 검증이 서비스로 새는 헌법 V 위반. 회원 요청은 `settings` 를 무시하지만 검증은 동일하게 적용한다(회원이 잘못된 형식을 보내도 400 — 계약은 인증 여부와 무관하게 하나).

## R10. 시각 공급 — `LocalDateTime.now()` 직접 호출

- **Decision**: `NotificationTokenService` 가 트랜잭션 메서드 진입 시 `LocalDateTime.now()` 를 한 번 잡아 그 트랜잭션의 `revoke`/`grant` 시각으로 쓴다. `Clock` 빈은 도입하지 않는다.
- **Rationale**: 프로젝트에 `Clock` 빈이 없고 기존 서비스(`FoodImageBatchCollectService`)도 직접 호출한다. 테스트는 시각의 정확값이 아니라 "찍혔다/유지됐다" 만 검증하므로 주입이 필요 없다.
- **Alternatives considered**: `Clock` 빈 주입 — 이 기능만을 위한 새 조립 코드. 필요해지는 시점(발송 시간대 가드, KB-471)에 도입.

## R11. API 버전 게이트 — 토큰 관리는 `X-API-Version 1.1` 이상에서만, 1.0 인증 API 무영향 (2026-09-07 사용자 결정)

- **Decision**: `PUT /api/notifications/tokens` 는 `@PutMapping("/tokens", version = "1.1+")` 로만 매핑한다(무버전 매핑 없음 → `1.0` 요청은 400). `AuthController` 는 기존 `login`·`logout`·`withdraw` 매핑(무버전 = 1.0)을 **그대로 두고**, 같은 경로에 `version = "1.1+"` 매핑 3개를 나란히 추가한다(`MemberController` 의 온보딩 `1.0`/`1.1+` 선례). 1.1+ 핸들러만 `X-Installation-Id` 를 읽고 `AuthService` 에 `installationId`/`releaseDevices` 를 넘긴다. 1.0 핸들러는 종전 호출(`login(idToken)`·`logout(refreshToken)`·`withdraw(memberId)`)을 유지해 기기·동의를 전혀 건드리지 않는다.
- **Rationale**: 배포된 1.0 앱의 회원가입·로그인·로그아웃·탈퇴 동작이 바뀌면 안 된다는 요구. 버전 번호는 앱 릴리스 마커라 이 릴리스에서 바뀌는 엔드포인트 4개가 모두 `1.1+` 를 쓴다(온보딩·프로필 `1.1+` 와 같은 번호 — 계약 규약 "같은 릴리스 = 같은 번호"). 1.0 앱은 토큰을 보내지 않으므로 1.0 탈퇴가 기기를 정리하지 않아도 정리할 기기가 없다. springdoc 은 `apiVersion11Doc` 그룹이 이미 있어 1.1 문서에 새 계약이 자동으로 실린다.
- **테스트 영향**: MockMvc 기본 헤더가 `1.0`(`MockMvcDefaultVersionConfig`)이라 새 테스트는 `X-API-Version: 1.1` 을 명시한다. 기존 `AuthControllerTest`(1.0)는 무수정으로 Green 이어야 하며, 이것이 "1.0 무영향" 의 회귀 증거다. 1.0 요청에 대한 토큰 API 응답은 Spring 의 버전 불일치 예외(`ErrorResponse`) → 400 COMMON-002 로 예상하며, 구현 시 실제 상태 코드를 확인해 404 가 나오면 원인을 기록한다.
- **Alternatives considered**: 1.0 핸들러도 헤더가 있으면 기기를 연결 — "1.0 무영향" 요구 위반. `AuthService` 메서드를 1.0/1.1 두 벌로 분리 — 로직 중복. 서비스는 기본 인자로 한 벌만 두고 컨트롤러 매핑만 둘로 나눈다.
