# Data Model: 비회원 광고 알림 동의 제거 (KB-543)

**스키마 변경 없음**(FR-006). 마이그레이션 파일을 추가하지 않는다. 아래는 엔티티(=도메인 모델) 수준의 변경만 적는다.

## NotificationConsent (`notification_consent`) — 광고성 수신 동의 원장

| 항목 | 변경 |
|------|------|
| `memberId: Long?` | 유지. 이번 변경 후 **새 행은 항상 non-null**(회원 귀속) — 코드 진입점이 `grantForMember` 뿐 |
| `installationId: String?` | 유지. 의미가 "게스트 기기 키" 에서 **"동의 받은 기기 출처"** 로 좁아진다(설정 API 의 `X-Installation-Id`, KB-466). 조회 키로 쓰지 않는다 |
| `consentType`·`consentVersion`·`grantedAt`·`revokedAt` | 불변 |
| `isOpen()`·`allows(version)`·`revoke(now)` | 유지 |
| `claim(memberId)` | **삭제** — 게스트 행을 회원 행으로 바꾸는 유일한 전이였다 |
| `companion.grantForMember(memberId, installationId?, type, version, now)` | 유지 |
| `companion.grantForInstallation(installationId, type, version, now)` | **삭제** |

상태 전이(변경 후): `open(memberId=X)` —`revoke(now)`→ `revoked`. 게스트→회원 인수 전이는 없다. 이미 저장된 `memberId = null` 행은 어떤 전이도 받지 않고 그대로 남는다(회원 기준 조회에 안 잡힘).

## Notification (`notification`) — 회원 알림함 원본

| 항목 | 변경 |
|------|------|
| `memberId: Long?` | 유지(타입 불변 — 컬럼이 nullable 이라 매핑 유지). 새 행은 `forMember` 로만 생성돼 항상 non-null |
| `installationId: String?` | **엔티티 필드 삭제**. DB 컬럼 `installation_id` 는 남긴다(`ddl-auto=validate` 는 엔티티에 없는 컬럼을 허용). 컬럼 제거는 별도 태스크 |
| `companion.forInstallation(...)` | **삭제** |
| `companion.forMember(...)`·`markRead`·`isRead` | 유지 |

## NotificationDevice (`notification_device`) — 기기 토큰 대장

변경 없음. `memberId: Long?` 는 로그아웃 상태를 표현하므로 nullable 유지. `register(..., memberId)` 는 이번 변경 후 항상 회원 id 로 호출되지만 시그니처(`Long? = null` 기본값)는 손대지 않는다 — 엔티티 API 축소는 얻는 게 없다.

## 리포지토리

| 리포지토리 | 변경 |
|-----------|------|
| `NotificationConsentJpaRepository.findOpenGuestByInstallationId` | **삭제**(설치 id 만으로 동의를 조회하는 유일한 진입점) |
| `NotificationConsentJpaRepository.findOpenByMemberId`·`closeOpenByMemberId` | 유지 |
| `NotificationJpaRepository` | 변경 없음(전부 `memberId` 기준) |
| `NotificationDeviceJpaRepository` | 변경 없음 |

## 검증 규칙(요청 경계)

- `NotificationTokenRegisterRequest`: `token`(NotBlank, ≤255)·`platform`(ios|android)·`lang`(NotBlank, ≤10) 유지. **`settings` 삭제**.
- `MarketingSettingsRequest` 클래스 삭제. 상수 `MAX_CONSENT_VERSION = 65535` 는 `NotificationSettingsUpdateRequest` 로 이동(설정 API 의 두 동의 버전 `@Max` 가 참조).
