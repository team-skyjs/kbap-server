# Research: 비회원 광고 알림 동의 제거 (KB-543)

Technical Context 에 NEEDS CLARIFICATION 은 없었다. 아래는 코드 조사로 확정한 설계 결정이다.

## R1. 비회원 401 강제 지점 — 필터 면제 제거 + 리졸버 교체

- **Decision**: `WebConfig` 의 `JwtAuthenticationFilter.GuestExemption("PUT", ^/api/notifications/tokens$)` 한 줄을 지운다. `/api/notifications`·`/api/notifications/*` 는 이미 `addUrlPatterns` 에 등록돼 있어 면제가 사라지면 필터가 토큰 없는 요청을 401 로 끊는다. 컨트롤러 파라미터는 `@AuthMemberIdOrNull memberId: Long?` → `@AuthMemberId memberId: Long`(인터페이스 `NotificationTokenApi` 시그니처도 `Long`).
- **Rationale**: 보호 경로의 단일 출처가 `WebConfig` 라는 기존 규약(CLAUDE.md "새 경로는 WebConfig 에 반드시 등록")을 그대로 쓴다. 컨트롤러·서비스에 `memberId == null → throw` 방어를 두는 것은 필터가 이미 하는 일의 중복이다. 401 응답 형식(`AUTH-*` 코드)은 다른 보호 경로와 동일해 FE 분기가 늘지 않는다.
- **Alternatives considered**: (a) 컨트롤러에서 null 검사 후 `BusinessException(AUTH_…)` — 필터 우회 경로를 하나 더 만들 뿐. (b) `@AuthMemberIdOrNull` 유지 + 서비스 `requireNotNull` — 타입이 계약을 말하지 못한다.

## R2. 요청 계약에서 `settings` 제거 — 모르는 필드는 무시

- **Decision**: `NotificationTokenRegisterRequest.settings` 필드와 `MarketingSettingsRequest` 클래스를 삭제한다. 구버전 앱이 `settings` 를 실어 보내면 Spring Boot 기본 Jackson 설정(`FAIL_ON_UNKNOWN_PROPERTIES=false`)이 무시하므로 200 이고 동의 원장은 변하지 않는다. `MarketingSettingsRequest.MAX_CONSENT_VERSION`(65535) 은 `NotificationSettingsUpdateRequest` 가 참조하므로 그 파일의 companion 으로 옮긴다.
- **Rationale**: spec US1-3 이 "무시되거나 거부" 를 모두 허용한다. 무시가 코드 0줄이고, 회원 요청의 `settings` 는 종전에도 무시됐으므로(KB-465) 회원 관점 동작 변화가 없다.
- **Alternatives considered**: `@JsonIgnoreProperties(ignoreUnknown = false)` 로 400 거부 — 다른 요청 DTO 와 정책이 달라지고, 구버전 앱의 회원 등록까지 깨뜨린다.

## R3. 로그인 병합 제거 — `linkOnLogin` 은 기기 연결만

- **Decision**: `NotificationTokenService.linkOnLogin` 을 `deviceRepository.findByInstallationId(installationId)?.linkMember(memberId)` 한 줄로 줄인다. 게스트 동의 조회·`claim`·`revoke` 분기와 `consentService` 의존을 제거한다. `closeOnWithdraw` 의 `consentRepository.closeOpenByMemberId` 는 회원 동의 정리라 유지(FR-004).
- **Rationale**: 병합은 게스트 동의가 존재해야만 의미가 있고, R1·R2 로 새 게스트 동의는 생기지 않는다. 기존에 남은 게스트 행은 spec Edge Case 대로 건드리지 않는다(회원 조회 `findOpenByMemberId` 는 `member_id` 기준이라 자연 배제).
- **Alternatives considered**: 잔존 게스트 행을 로그인 시 일괄 철회 — "기존 행은 삭제·이동하지 않는다" 는 spec Assumptions 위반이고 증빙 원장을 건드린다.

## R4. 설치 id 전용 진입점 삭제 범위 — 메서드는 지우고 컬럼·출처 필드는 남긴다

- **Decision**: 삭제 — `NotificationConsentService.grantForInstallation`·`revokeForInstallation`, `NotificationConsentJpaRepository.findOpenGuestByInstallationId`, `NotificationConsent.grantForInstallation`·`claim`, `Notification.forInstallation`. 유지 — `NotificationConsent.installationId` 필드와 `grantForMember(memberId, installationId?, …)` 의 출처 인자(설정 API 가 `X-Installation-Id` 를 "동의 받은 기기" 로 기록, KB-466). `Notification.installationId` 필드는 읽는 곳이 없으므로 엔티티에서 제거하되 DB 컬럼은 둔다(Hibernate `ddl-auto=validate` 는 엔티티에 없는 DB 컬럼을 허용).
- **Rationale**: FR-005 는 "설치 id **만으로**" 생성·조회·철회하는 진입점 금지다. 회원 동의의 출처 기록은 회원 id 가 주키인 행에 붙는 부가 정보라 대상이 아니다. `Notification` 의 설치 id 는 발송 파이프라인(KB-468)이 회원 기기만 대상으로 하므로 영구 미사용 — 지금 지워야 KB-468 이 비회원 분기를 고려하지 않는다.
- **Alternatives considered**: `Notification.installationId` 필드 유지 — 쓰는 코드가 없는데 nullable 필드가 남아 "비회원 알림이 가능한가" 라는 오독을 만든다. 컬럼까지 지우는 마이그레이션 — 블루/그린 중 구 코드가 `installation_id` 를 SELECT/INSERT 하다 깨진다(`schema-change-revision-coexistence`), 별도 태스크.

## R5. 테스트 재편 — 게스트 시나리오를 401·불변 시나리오로 치환

- **Decision**:
  - `NotificationTokenControllerTest`: "게스트로 등록" 계열 → "토큰 없이 등록하면 401 이고 기기 행이 생기지 않는다" 하나로 대체. 나머지 등록·갱신·멱등 시나리오는 회원 토큰으로 수행. "게스트 K-Bap 소식 동의" given 블록 전체 삭제, 대신 "회원이 옛 `settings` 를 실어 보내면 200 이고 원장 불변" 한 시나리오만 남긴다(US1-3).
  - `AuthNotificationLinkTest`: "게스트 기기에서 로그인" 의 인수·철회 3개 시나리오 → "잔존 게스트 동의 행이 있는 기기에서 로그인해도 기기만 연결되고 원장은 한 행도 바뀌지 않는다"(US2-1, `insertOpenGuestConsent` 헬퍼 재사용). `registerToken` 헬퍼는 `accessToken` 필수·`marketingVersion` 제거. "설치 → 등록 → 로그인 → …" 흐름은 로그인 → 등록 → 로그아웃 → 재로그인 순서로 재배열. 1.0 무영향 given 의 게스트 등록도 회원 토큰 등록으로 바꾼다.
  - common: `NotificationConsentJpaRepositoryTest` "게스트 동의와 로그인 인수" given, `NotificationJpaRepositoryTest` "게스트 기기 대상 알림" given 삭제(삭제 메서드 참조라 컴파일 실패로 Red).
- **Rationale**: 원칙 I — 삭제되는 동작의 테스트는 컴파일 오류로 Red 가 드러나고, 새 강제(401·불변)는 구현 전 실패로 Red 를 확인한다. 통합 컨텍스트 헤더(`@IntegrationTest`)와 `TestTables.clearAll` 규약은 그대로.
- **Alternatives considered**: 게스트 등록 테스트를 남기고 `.status shouldBe 401` 만 바꾸기 — 동의 given 블록의 8개 시나리오가 전부 401 로 수렴해 의미 없는 중복이 된다.
