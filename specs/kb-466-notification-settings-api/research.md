# Research: 회원 알림 설정 API — 활동 푸시·K-Bap 소식 두 그룹 재편

선행: KB-464(저장 기반, #246)·KB-465(토큰 등록·인증 연동, #247) 머지 상태(develop ec832d0c). 이 기능은 그 위에 회원 설정 API 를 얹으면서 저장 구조를 두 그룹 모델에 맞게 바꾼다.

## R1. URL·버전 — `/api/notifications/settings` GET/PATCH, 무버전 매핑(1.0 부터)

- **Decision**: `GET`·`PATCH ${ApiPaths.API}/notifications/settings`, **무버전 매핑**(`version` 속성 없음 → `X-API-Version 1.0` 부터 동작). 처음 만들어지는 API 라 구 계약이 없고 버전 게이트를 둘 이유가 없다(2026-09-07 사용자 결정 — KB-465 토큰 API 의 1.1+ 게이트는 기존 인증 API 와 묶여 있어서였고 여기엔 해당 없음). 컨트롤러는 `api.notification.NotificationSettingController`(+`NotificationSettingApi` swagger 인터페이스).
- **Rationale**: KB-465 R1 이 `/api/notifications/*` 베이스와 `settings` 경로를 이미 예약했고, 알림 기능이 한 패키지·한 베이스에 응집된다. Jira 초안 `/api/members/me/notification-settings` 는 회원 리소스 아래 알림 하위를 두는 구조라 KB-465 결정과 어긋난다.
- **Alternatives**: Jira 초안 경로 — KB-465 결정 우선. `1.1+` 게이트 — 신규 API 에는 감출 구 계약이 없어 불필요.

## R2. 인증 — JWT 필터 등록 + `@AuthMemberId`

- **Decision**: `WebConfig.jwtAuthenticationFilterRegistration.addUrlPatterns` 에 `"${ApiPaths.API}/notifications/settings"` 를 **정확 경로**로 추가한다. 컨트롤러는 `@AuthMemberId memberId: Long`. 게스트는 필터에서 401.
- **Rationale**: 회원 전용(FR-012). `/notifications/*` 와일드카드를 쓰면 KB-465 의 `PUT /notifications/tokens`(인증 선택, 필터 미등록)까지 걸려 게스트 토큰 등록이 깨진다.
- **Alternatives**: `/notifications/*` + GuestExemption — 예외 목록만 늘어난다.

## R3. 선호 저장 구조 — `helpful`·`review_reminder` → `activity`·`meal_time`

- **Decision**: `notification_setting` 컬럼을 `activity BOOLEAN NOT NULL DEFAULT TRUE`, `meal_time BOOLEAN NOT NULL DEFAULT TRUE` 로 교체한다(구 컬럼 DROP). 엔티티 `NotificationSetting(memberId, activity = true, mealTime = true)`, 메서드 `updateActivity`·`updateMealTime`, `defaultFor(memberId)`. `NotificationPreferences` 값 객체는 `(activity, mealTime)` 로 축소.
- **Rationale**: 활동/소식 토글 하나가 도움됨·리마인더를 함께 제어(FR-004), 식사 시간 알림 토글 하나가 점심·저녁을 함께 제어(FR-005). `meal_time` 기본 TRUE 로 두면 "K-Bap 소식을 처음 켤 때 식사 시간 알림이 켜진다"(FR-008)와 "껐다 켜면 이전 값 복원"(US3-8)이 tri-state 없이 성립한다 — 응답의 `mealTime` 은 `저장값 AND 소식 켜짐` 으로 계산하므로 소식이 꺼진 회원에겐 항상 false 로 보인다(FR-002).
- **Alternatives**: `meal_time NULL = 미설정` tri-state — 분기만 늘고 이득 없음. 기존 두 컬럼 유지 + 앱에서 묶기 — 정본이 앱 화면과 달라져 배치 필터가 두 컬럼을 AND 로 봐야 하는 암묵 규칙이 생긴다.

## R4. 동의 원장에 종류 축 — `consent_type` 컬럼 + enum `NotificationConsentType`

- **Decision**: `notification_consent` 에 `consent_type VARCHAR(30) NOT NULL` 을 추가한다. enum `NotificationConsentType { MARKETING_PRIVACY, MARKETING_RECEIVE }`(`@Enumerated(STRING)`). 기존 행은 `MARKETING_RECEIVE` 로 백필(마이그레이션에서 DEFAULT 로 채운 뒤 DEFAULT 제거). 인덱스는 손대지 않는다 — 회원·기기당 열린 행이 종류별 1개 수준이라 기존 `(member_id, revoked_at)`·`(installation_id, revoked_at)` 로 읽고 종류는 앱이 거른다.
- **Rationale**: 두 동의(개인정보 수집·이용 / 광고성 수신)를 종류별로 언제 어떤 버전에 동의·철회했는지 남겨야 한다(FR-007·SC-003). 행 1개 = 동의 1회의 생애 구조는 그대로.
- **Alternatives**: 동의 종류별 테이블 2개 — 같은 구조 복제. 한 행에 두 버전 컬럼 — 종류별 철회 시각을 못 남긴다.

## R5. "K-Bap 소식 켜짐" 판정 — 저장값이 아니라 두 종류의 열린 동의 존재

- **Decision**: `kbapNews.enabled = openConsents.any{PRIVACY} && openConsents.any{RECEIVE}`. 별도 boolean 컬럼을 두지 않는다. 리포지토리 `findOpenByMemberId` 결과를 종류별로 나눠 판정한다.
- **Rationale**: 정본이 하나(원장)여야 "한쪽만 철회된 과거 데이터" 같은 엣지가 자동으로 꺼짐 처리된다(FR-011). 발송 배치도 같은 질의로 판정한다.
- **Alternatives**: 설정 테이블에 `marketing` 컬럼 캐시 — 원장과 어긋날 수 있다.

## R6. 서비스 경계 — `NotificationSettingService` + 공유 `NotificationConsentService`

- **Decision**: `api.notification.NotificationSettingService`(`@Service`)에 `getSettings(memberId): NotificationSettingsResult`(`readOnly`)·`updateSettings(memberId, installationId?, command): NotificationSettingsResult`(`@Transactional`) 두 메서드. 동의 원장 조작(종류·버전 비교 후 grant/revoke)은 `api.notification.NotificationConsentService` 로 뽑아 토큰 서비스(게스트)와 설정 서비스(회원)가 공유한다: `grantForMember(memberId, installationId?, versions, now)`·`revokeForMember(memberId, now)`·`grantForInstallation(installationId, versions, now)`·`revokeForInstallation(installationId, now)`. `versions` 는 `Map<NotificationConsentType, Int>`.
- **Rationale**: KB-465 R7 의 "같은 버전 무변화·다른 버전은 닫고 새 행" 규칙이 회원·게스트에 동일하게 적용되는데(FR-007), 두 서비스에 복제하면 어긋난다. 도메인 규칙(버전 비교)은 서비스 하나가 소유.
- **Alternatives**: `NotificationConsent` 엔티티 companion 에 정책 — 리포지토리 조회가 필요해 엔티티가 못 가진다. 토큰 서비스에 두고 설정 서비스가 호출 — 토큰 서비스가 "설정" 관심사를 갖게 된다.

## R7. 부분 수정 계약 — 중첩 객체, 켜기 요청에 두 버전 필수

- **Decision**: PATCH 본문 `{ activity?: bool, kbapNews?: { enabled?: bool, mealTime?: bool, privacyConsentVersion?: int, receiveConsentVersion?: int } }`. `kbapNews.enabled == true` 면 두 버전 모두 필수(DTO `@get:AssertTrue`, 400 COMMON-002). `enabled == false` 면 버전 무시. `mealTime == true` 인데 (이 요청 반영 후) 소식이 꺼져 있으면 400 `NOTIFICATION-001 MARKETING_CONSENT_REQUIRED`. `mealTime == false` 는 소식 상태와 무관하게 저장(값 보존 규칙). 응답은 항상 GET 과 같은 전체 설정.
- **Rationale**: 화면 구조(그룹 → 하위)를 그대로 옮기면 필드 이름이 자명하다. 켜기·하위 토글을 한 요청에 보내는 경우(`enabled=true, mealTime=false`)도 순서 규칙("enabled 먼저 반영")으로 결정적이다.
- **Alternatives**: 평탄한 필드(`marketing`, `mealTime`, …) — Jira 초안. 그룹 소속이 이름에 안 드러나 K-Bap 소식 재편 의도가 사라진다.

## R8. 게스트 토큰 등록 계약 조정 (FR-013)

- **Decision**: `MarketingSettingsRequest` 를 `{ marketing: bool, privacyConsentVersion?: int, receiveConsentVersion?: int }` 로 바꾼다(`marketingConsentVersion` 삭제). `marketing=true` 면 두 버전 필수. `NotificationTokenService.applyGuestMarketingConsent` 는 `NotificationConsentService.grantForInstallation/revokeForInstallation` 위임으로 축소. 로그인 인수(`linkOnLogin`)는 **종류별**로 판단한다: 게스트 열린 행 각각에 대해 회원에게 같은 종류의 열린 행이 있으면 `revoke`, 없으면 `claim`.
- **Rationale**: 원장에 종류 축이 생기면 게스트 동의도 두 종류를 남겨야 배치 필터(두 종류 모두 열림)가 게스트를 대상에 넣는다. 종류별 인수는 "회원 동의가 정본" 규칙을 종류 단위로 지킨다.
- **Alternatives**: 게스트는 RECEIVE 한 종류만 — 배치가 게스트에 다른 기준을 써야 한다.

## R9. 에러 코드 — `NOTIFICATION-001`

- **Decision**: `ErrorCode.MARKETING_CONSENT_REQUIRED("NOTIFICATION-001", 400, "K-Bap 소식 수신 동의 후 설정할 수 있습니다")` 추가. 그 외 검증 실패는 기존 `COMMON-002`.
- **Rationale**: 앱이 "동의 화면으로 유도" 분기를 code 로 해야 한다.

## R10. 시각 공급·트랜잭션

- **Decision**: KB-465 R10 그대로 — 서비스 트랜잭션 메서드 진입 시 `LocalDateTime.now()` 한 번. `updateSettings` 는 단일 `@Transactional`(선호 갱신·동의 grant/revoke 모두 관리 엔티티 dirty checking + `save` 신규 행). 외부 호출 없음.

## R11. 마이그레이션 — 새 파일 하나, 컬럼 교체 + 종류 추가

- **Decision**: `V2026.09.07.21.07.25__notification_setting_two_groups.sql` 한 파일: `notification_setting` DROP `helpful`·`review_reminder`, ADD `activity`·`meal_time`; `notification_consent` ADD `consent_type` (DEFAULT 'MARKETING_RECEIVE' 로 추가 후 `ALTER ... DROP DEFAULT`). KB-464 파일은 손대지 않는다(공유 DB 적용 가정).
- **Rationale**: CLAUDE.md 마이그레이션 규칙. 순서 비의존 — 참조 테이블은 KB-464 파일이 만들지만 out-of-order 로 이 파일이 먼저 도는 경우는 없다(KB-464 가 develop 에 먼저 머지됨). 프로덕션 미배포 테이블이라 데이터 손실 걱정 없음.

## R12. 알림 유형 enum 은 유지

- **Decision**: `NotificationType { HELPFUL, SCAN_SUGGESTION, REVIEW_REMINDER, NOTICE }` 를 바꾸지 않는다. 토글 대응(HELPFUL·REVIEW_REMINDER → activity, SCAN_SUGGESTION → mealTime, NOTICE → 미정)은 data-model 에 표로만 남기고 소비는 발송 배치 태스크가 한다.
- **Rationale**: 이 기능의 소비자가 없는 변경(YAGNI). 이름 변경은 배치가 유형을 실제로 쓸 때 한 번에.

## R13. 테스트 범위

- **Decision**: (1) `api` 통합 `NotificationSettingControllerTest`(BehaviorSpec, `@IntegrationTest`) — spec 시나리오 전부. (2) 기존 `NotificationTokenControllerTest` 의 게스트 동의 시나리오를 두 종류 버전 계약으로 갱신 + 로그인 종류별 인수. (3) `common` 리포지토리 테스트 `NotificationSettingJpaRepositoryTest`·`NotificationConsentJpaRepositoryTest` 를 새 컬럼에 맞게 갱신. 엔티티 순수 단위 테스트는 `NotificationSetting`·`NotificationConsent` 의 기존 파일이 있으면 갱신, 없으면 통합으로 충분.
