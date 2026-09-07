# Data Model: 푸시 토큰 등록 API 와 기기-회원 연결

스키마·엔티티는 KB-464([data-model.md](../kb-464-push-notification-schema/data-model.md))가 확정했고 이 기능은 **바꾸지 않는다**. 여기서는 이 기능이 일으키는 상태 전이와 각 사건이 호출하는 도메인 메서드·리포지토리, 트랜잭션 경계를 고정한다.

## 사용하는 영속 객체 (변경 없음)

| 엔티티 | 테이블 | 이 기능이 쓰는 도메인 메서드 | 이 기능이 쓰는 리포지토리 메서드 |
|---|---|---|---|
| `NotificationDevice` | `notification_device` (UK `installation_id`) | `register(installationId, expoToken, platform, lang, memberId?)` · `renew(expoToken, platform, lang)`(무효 스탬프 해제 포함) · `linkMember(memberId)` · `unlinkMember()` | `findByInstallationId` · `findByMemberId` · `save`(신규만) |
| `NotificationConsent` | `notification_consent` | `grantForInstallation(installationId, version, now)` · `revoke(now)` · `claim(memberId)` | `findOpenGuestByInstallationId` · `findOpenByMemberId` · `closeOpenByMemberId(memberId, now)` · `save`(신규만) |

`NotificationDevice` 에 `delete()` 를 호출하는 경로는 없다. `NotificationConsent` 는 어떤 사건에서도 행을 지우지 않는다(FR-004·FR-015).

## 기기 기록 상태 전이

기기 행의 관찰 가능한 상태는 `(memberId, tokenInvalidAt)` 두 축이다.

```
                 PUT(게스트)                    PUT(회원 m)
   [없음] ───────────────────▶ [게스트, 유효] ◀────────────┐
      │                            │  ▲                    │
      │ PUT(회원 m)                 │  │ logout(기기)       │ PUT(회원 m) — memberId 를 m 으로 덮어씀
      ▼                            ▼  │ withdraw(m)        │ (다른 회원이 연결돼 있어도 동일)
   [회원 m, 유효] ◀────────────── login(m, 기기)            │
      │                                                   │
      │ 영수증 정리(KB-473) — 이 기능 밖                    │
      ▼                                                   │
   [회원 m, 무효] ─── PUT(누구든) → renew 가 무효 해제 ──────┘
```

- **[없음] → 생성**: `PUT` 만 행을 만든다. `login` 은 만들지 않는다(FR-013, US3-AS3).
- **`PUT`**: `findByInstallationId` 가 null 이면 `register(..., memberId = 인증 회원 또는 null)` 후 `save`. 있으면 `renew(token, platform, lang)`, 그리고 인증 회원이 있으면 `linkMember(memberId)`. 게스트 `PUT` 은 기존 `memberId` 를 **건드리지 않는다** — 회원 기기가 앱 재실행으로 토큰을 보내는 정상 경로에서 연결이 풀리면 안 되며, 탈퇴 후 게스트 `PUT` 은 이미 탈퇴가 연결을 비운 뒤라 결과가 같다(Edge Case "탈퇴 후 같은 기기에서 게스트로 토큰").
- **`login(m)`**: 있으면 `linkMember(m)` — 이전 연결 회원이 누구든 덮어쓴다(Edge Case "회원 A 기기에서 B 로그인").
- **`logout`**: 있으면 `unlinkMember()`. 헤더 없음·미등록이면 무처리.
- **`withdraw(m)`**: `findByMemberId(m)` 전부 `unlinkMember()`.

## 동의 원장 사건 표 (이 기능이 일으키는 사건만)

`now` 는 트랜잭션 진입 시 한 번 잡은 `LocalDateTime.now()`.

| 사건 | 전제 조회 | 원장 조작 |
|---|---|---|
| 게스트 `PUT`, `settings.marketing=true, version=v` | `open = findOpenGuestByInstallationId(i)` | `open.filter { it.consentVersion != v }.forEach { revoke(now) }`; `open.none { it.consentVersion == v }` 이면 `save(grantForInstallation(i, v, now))` |
| 게스트 `PUT`, `settings.marketing=false` | 동일 | `open.forEach { revoke(now) }` (비어 있으면 무처리) |
| 게스트 `PUT`, `settings` 없음 | 없음 | 무처리 |
| 회원 `PUT` (settings 유무 무관) | 없음 | 무처리 (FR-007) |
| `login(m, i)` | `guest = findOpenGuestByInstallationId(i)`; 비어 있지 않으면 `mine = findOpenByMemberId(m)` | `mine` 비어 있음 → `guest.forEach { claim(m) }`(`grantedAt` 보존). 있음 → `guest.forEach { revoke(now) }` |
| `logout` | 없음 | 무처리 (FR-011) |
| `withdraw(m)` | 없음 | `closeOpenByMemberId(m, now)` (bulk UPDATE, 행 보존) |

`claim` 은 엔티티 불변식(`memberId == null && isOpen()`)을 `check` 로 지키는데, `findOpenGuestByInstallationId` 가 그 조건으로만 조회하므로 정상 경로에서 위반하지 않는다.

## 트랜잭션 경계

| 메서드 (`NotificationTokenService`) | 경계 | 호출자 |
|---|---|---|
| `registerToken(installationId, memberId?, token, platform, lang, marketing?)` | `@Transactional` — 기기 upsert + 게스트 동의 반영이 한 단위 | `NotificationTokenController.register` |
| `linkOnLogin(installationId, memberId)` | `@Transactional` — 기기 연결 + 동의 인수/철회가 한 단위 | `AuthService.login(idToken, installationId)` — 1.1+ 핸들러가 헤더를 넘겼을 때만, `findOrSignUp`·토큰 발급 뒤 |
| `unlinkOnLogout(installationId)` | `@Transactional` | `AuthService.logout(refreshToken, installationId)` — 1.1+ 핸들러가 헤더를 넘겼을 때만, refresh 폐기와 무관하게 실행 |
| `closeOnWithdraw(memberId)` | `@Transactional` — 기기 전부 unlink + `closeOpenByMemberId` 가 한 단위 | `AuthService.withdraw(memberId, releaseDevices = true)` — 1.1+ 핸들러만. 소셜 삭제 뒤, `memberService.withdraw` 앞(research R5) |

`AuthService` 는 종전대로 트랜잭션이 없다. 기기 처리 메서드는 각자 트랜잭션을 열며, 인증 처리(토큰 발급·refresh 폐기·회원 마킹)와는 분리된 단위다. **1.0 핸들러(무버전 매핑)는 기본 인자(`installationId = null`·`releaseDevices = false`)로 호출하므로 이 표의 어떤 메서드도 실행되지 않는다**(research R11).

## 요청 값 → 확정값

| 요청 필드 | 검증(요청 경계) | 서비스가 받는 타입 |
|---|---|---|
| 헤더 `X-API-Version` | 토큰 등록: `1.1` 이상만 매핑(1.0 → 404). 인증: 1.0 은 종전 핸들러, 1.1+ 는 기기 연동 핸들러 | (라우팅) |
| 헤더 `X-Installation-Id` | 등록: 필수·`@NotBlank`·`@Size(max=36)`. 로그인·로그아웃(1.1+): 선택. 1.0 핸들러는 읽지 않음 | `String` / `String?` |
| `token` | `@NotBlank`·`@Size(max=255)` | `String` |
| `platform` | `@NotBlank`·`@Pattern("(?i)ios\|android")` → 컨트롤러가 `DevicePlatform.valueOf(uppercase)` | `DevicePlatform` |
| `lang` | `@NotBlank`·`@Size(max=10)` (검증·정규화 없음 — KB-464 R3) | `String` |
| `settings.marketing` | `@NotNull` (settings 가 있을 때) | `Boolean` |
| `settings.marketingConsentVersion` | `@Positive`; `marketing=true` 면 `@get:AssertTrue` 로 필수 | `Int?` — 검증을 통과한 중첩 DTO `MarketingSettingsRequest?` 를 그대로 서비스에 넘긴다(같은 기능 패키지, 변환 클래스 없음) |
| 인증 회원 | `@AuthMemberIdOrNull` (위조·만료 토큰은 401) | `Long?` |
