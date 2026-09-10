---

description: "KB-543 비회원 광고 알림 동의 제거 — 태스크 목록"
---

# Tasks: 비회원 광고 알림 동의 제거 — 알림 대상을 회원 전용으로

**Input**: Design documents from `/specs/kb-543-member-only-notification/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R5), data-model.md, contracts/notification-token.md, quickstart.md

**Tests**: Test-First 는 필수(헌법 원칙 I). 각 스토리는 실패 테스트를 먼저 쓰고 Red 를 확인한 뒤 삭제·축소 구현으로 Green 을 만든다. 이 기능은 **순수 삭제 작업**이라 새 파일이 없고, 새 강제(401·원장 불변)만 신규 시나리오다.

**Organization**: 스토리별 phase. US1·US2 는 같은 서비스 파일(`NotificationTokenService`)을 만지므로 순서대로 진행한다(US1 → US2). US3 는 US1·US2 가 끝나야 삭제 대상 호출자가 사라진다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·선행 의존 없음 — 병렬 가능
- **[Story]**: US1·US2·US3
- 경로는 repo 루트 기준

## Path Conventions

- api 메인 `api/src/main/kotlin/com/kbap/api/`, api 테스트 `api/src/test/kotlin/com/kbap/api/`
- common 메인 `common/src/main/kotlin/com/kbap/common/domain/notification/`, common 테스트 `common/src/test/kotlin/com/kbap/common/domain/notification/`

---

## Phase 1: Setup

**없음** — 기존 모듈·패키지 안에서 삭제·축소만 한다. 신규 파일·의존성·마이그레이션 없음(FR-006).

---

## Phase 2: Foundational

**없음** — 인증 필터(`JwtAuthenticationFilter`)·`@AuthMemberId` 리졸버·보호 경로 등록(`/api/notifications/*`)은 이미 존재한다(research R1).

---

## Phase 3: User Story 1 - 비회원은 기기 토큰을 등록할 수 없다 (Priority: P1) 🎯 MVP

**Goal**: `PUT /api/notifications/tokens` 를 회원 전용으로 닫고(401), 요청 계약에서 게스트 동의 `settings` 를 제거한다. 회원 등록은 종전과 같다.

**Independent Test**: 토큰 없이 등록 → 401, `notification_device` 행 없음. 회원 토큰으로 등록 → 200, 행 생성·회원 연결. 회원이 옛 `settings` 를 실어 보내도 200 이고 `notification_consent` 행 수 불변.

### Tests for User Story 1 (Test-First — 먼저 쓰고 FAIL 확인) ⚠️

- [X] T001 [US1] `api/src/test/kotlin/com/kbap/api/notification/NotificationTokenControllerTest.kt` 재편 — (a) `register` 헬퍼에서 `settings` 인자 제거, 기본 호출은 회원 access token 으로 수행(`FakeSocialTokenVerifier` 로그인 → 토큰 취득은 기존 헬퍼 재사용). (b) given "기기 토큰 등록" 의 게스트 시나리오("서버가 모르는 기기가 게스트로…", "게스트로 등록된 기기에 회원 인증을 붙여…", "회원에 연결된 기기가 게스트로 다시 보내면") 를 **"Authorization 없이 등록하면 401 이고 기기 행이 생기지 않는다"** 한 시나리오로 대체하고, 갱신·무효 토큰 되살림·멱등 시나리오는 회원 토큰으로 수행하되 "회원 연결이 채워진다" 를 단언. (c) given "게스트 K-Bap 소식 동의" 블록 전체 삭제, 단 "회원 인증이 붙은 요청에 동의 값을 실어 보내면 원장은 바뀌지 않고 토큰 등록만 처리된다" 시나리오는 given "기기 토큰 등록" 아래로 옮겨 유지(US1-3, `settings` 는 `Map` 으로 실어 보냄). (d) given "잘못된 등록 요청" 은 회원 토큰으로 유지. 실행 `./gradlew :api:test` → 401 시나리오가 **200 으로 실패(Red)** 함을 확인.

### Implementation for User Story 1

- [X] T002 [P] [US1] `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` 에서 `JwtAuthenticationFilter.GuestExemption("PUT", Regex("^${ApiPaths.API}/notifications/tokens$"))` 한 줄 삭제(research R1). `addUrlPatterns` 는 이미 `/api/notifications/*` 포함 — 추가 등록 없음.
- [X] T003 [P] [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenRegisterRequest.kt` 에서 `settings` 필드와 `MarketingSettingsRequest` 클래스 삭제(불필요 import `Valid`·`AssertTrue`·`Max`·`NotNull`·`Positive` 정리). `MAX_CONSENT_VERSION = 65535L` 상수는 `api/src/main/kotlin/com/kbap/api/notification/NotificationSettingsUpdateRequest.kt` 의 참조 클래스 companion 으로 옮기고 `@Max`·메시지 참조를 그 상수로 바꾼다(research R2).
- [X] T004 [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenController.kt` — `@AuthMemberIdOrNull memberId: Long?` → `@AuthMemberId memberId: Long`(import 교체), `registerToken(...)` 호출에서 `settings = request.settings` 제거. `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenApi.kt` — `register` 시그니처 `memberId: Long` 으로 변경(서술 문구 교체는 T014).
- [X] T005 [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenService.kt` — `registerToken(installationId, memberId: Long, token, platform, lang)` 로 축소: `settings` 파라미터·`applyGuestMarketingConsent` 삭제, `memberService.getMember(memberId)` 후 `upsertDevice` 가 항상 `linkMember` 하도록 `upsertDevice` 의 `memberId: Long` 비-null 화(신규 등록은 `NotificationDevice.register(..., memberId)`, 기존은 `renew` + `linkMember`). 생성자에서 `consentService: NotificationConsentService` 의존 제거(`consentRepository` 는 `closeOnWithdraw` 가 쓰므로 유지). 불필요 import(`NotificationConsentType`) 정리.
- [X] T006 [US1] `./gradlew :api:test` 실행 — `NotificationTokenControllerTest` Green, `NotificationSettingControllerTest`·`NotificationInboxTest` 회귀 통과 확인. (`AuthNotificationLinkTest` 는 게스트 등록 헬퍼 때문에 이 시점에 401 로 깨진다 — US2 에서 고친다.)

**Checkpoint**: 비회원 등록 입구가 닫혔고 계약에서 `settings` 가 사라졌다. 커밋.

---

## Phase 4: User Story 2 - 로그인·로그아웃·탈퇴 시 기기 연결만 다루고 게스트 동의 병합은 하지 않는다 (Priority: P1)

**Goal**: 로그인 시 `linkOnLogin` 이 기기 연결만 하고 게스트 동의 인수(`claim`)·철회 병합을 하지 않는다. 로그아웃·탈퇴 동작은 불변(FR-004).

**Independent Test**: 설치 id 로 잔존 게스트 동의 행을 직접 INSERT 한 뒤 그 기기로 로그인 → 기기는 회원에 연결되고 `notification_consent` 는 한 행도 바뀌지 않는다. 로그아웃·탈퇴 회귀 시나리오 통과.

### Tests for User Story 2 (Test-First — 먼저 쓰고 FAIL 확인) ⚠️

- [X] T007 [US2] `api/src/test/kotlin/com/kbap/api/auth/AuthNotificationLinkTest.kt` 재편 — (a) `registerToken(installationId, accessToken)` 헬퍼에서 `accessToken` 을 필수 인자로, `marketingVersion`·`settings` 조립 삭제. (b) given "게스트 기기에서 로그인" 의 병합 시나리오 3개("게스트 동의가 있고 회원에게 유효한 동의가 없으면", "게스트 동의가 두 건 열려 있으면", "회원에게 이미 유효한 동의가 있으면") 를 **"잔존 게스트 동의 행이 있는 기기에서 로그인하면 기기만 회원에 연결되고 동의 원장은 한 행도 바뀌지 않는다"** 하나로 대체(`insertOpenGuestConsent` 헬퍼로 두 종류 행 INSERT → `consentsOf("1 = 1")` 전후 동일 단언 + `openConsentsOfMember(memberId)` 비어 있음). 기기 사전 등록은 로그인 → 등록 → 로그아웃(연결 해제) 순으로 만든다. (c) given "설치 → 등록 → 로그인 → 로그아웃 → 재로그인" 을 **로그인 → 등록 → 로그아웃 → 재로그인** 으로 재배열(등록 직후 연결 회원 단언, 로그아웃 후 null, 재로그인 후 재연결, 기기 수 1). (d) given "1.0 인증 API 무영향" 의 게스트 등록을 회원 등록 + 1.1 로그아웃으로 연결 해제한 상태로 바꾸고, 1.0 로그인 뒤 `deviceMemberId` null 유지 단언(동의 단언은 `countConsents()` 불변으로). (e) 나머지(다른 회원 로그인·로그아웃·탈퇴·헤더 없음·두 번 로그인) 는 헬퍼 시그니처만 맞춘다. 실행 → (b) 시나리오가 **게스트 행의 member_id 가 채워져 실패(Red)** 함을 확인.

### Implementation for User Story 2

- [X] T008 [US2] `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenService.kt` — `linkOnLogin(installationId, memberId)` 본문을 `deviceRepository.findByInstallationId(installationId)?.linkMember(memberId)` 한 줄로 축소(게스트 동의 조회·`memberOpenTypes`·`claim`/`revoke` 분기 삭제, research R3). `unlinkOnLogout`·`closeOnWithdraw` 불변.
- [X] T009 [US2] `./gradlew :api:test` 실행 — `AuthNotificationLinkTest` Green, `NotificationTokenControllerTest`·`NotificationSettingControllerTest`·`NotificationInboxTest`·`AuthControllerTest` 계열 회귀 통과 확인.

**Checkpoint**: 비회원 경로의 입구(US1)와 병합(US2)이 모두 닫혔다. 커밋.

---

## Phase 5: User Story 3 - 비회원 알림·비회원 동의를 만드는 경로가 코드에 남지 않는다 (Priority: P2)

**Goal**: 설치 id 만으로 동의·알림을 생성·조회·철회하는 진입점을 전부 삭제하고, API 문서를 회원 전용으로 고친다. 스키마 무변경.

**Independent Test**: quickstart §2 grep 이 0건, `git diff develop -- api/src/main/resources/db/migration` 이 비어 있고, `./gradlew build`(ArchUnit 포함) 통과. Swagger 에 `settings`·게스트 서술 없음.

### Tests for User Story 3 (Test-First) ⚠️

> 이 스토리의 Red 는 **컴파일 실패**다 — 삭제 대상 메서드를 참조하는 common 테스트 블록을 먼저 지우고, 이어서 메서드를 지우면 남은 호출자가 컴파일 오류로 드러난다. 최종 검증은 quickstart 의 grep·build 다.

- [X] T010 [P] [US3] `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationConsentJpaRepositoryTest.kt` 에서 given "게스트 동의와 로그인 인수" 블록(세 `when`) 삭제, 불필요 import(`shouldBeEmpty`·`shouldBeNull` 등 다른 곳에서 안 쓰면) 정리.
- [X] T011 [P] [US3] `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationJpaRepositoryTest.kt` 에서 given "게스트 기기 대상 알림" 블록 삭제, 불필요 import 정리.

### Implementation for User Story 3

- [X] T012 [P] [US3] `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationConsent.kt` — `claim(memberId)` 와 companion `grantForInstallation(...)` 삭제. `installationId` 필드·`grantForMember(memberId, installationId?, …)` 는 유지(동의 받은 기기 출처, research R4). `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationConsentJpaRepository.kt` — `findOpenGuestByInstallationId` 삭제.
- [X] T013 [P] [US3] `common/src/main/kotlin/com/kbap/common/domain/notification/model/Notification.kt` — companion `forInstallation(...)` 과 `installationId` 필드 삭제(DB 컬럼은 유지 — 마이그레이션 없음, `ddl-auto=validate` 통과).
- [X] T014 [US3] `api/src/main/kotlin/com/kbap/api/notification/NotificationConsentService.kt` — `grantForInstallation`·`revokeForInstallation` 삭제(`grantForMember`·`revokeForMember`·`isMarketingEnabled`·private `grant` 유지).
- [X] T015 [US3] `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenApi.kt` — `@Operation.description` 에서 "게스트는 Authorization 없이…" 문단과 "게스트 K-Bap 소식 동의(settings…)" 문단 삭제, "**회원 전용** — 로그인 후 호출한다. 기기는 요청 회원에 연결된다" 로 교체. `@ApiResponse` 400 설명에서 `marketing=true 인데 버전 없음` 삭제, 401 설명을 "Authorization 없음·위조·만료" 로(contracts/notification-token.md 기준, FR-007).
- [X] T016 [US3] `./gradlew build` 실행(common·api·batch 컴파일 + 전 테스트 + ArchUnit) 통과 확인. quickstart §2 grep(`grantForInstallation|revokeForInstallation|findOpenGuestByInstallationId|forInstallation\(|fun claim\(|MarketingSettingsRequest` → main 소스 0건, `WebConfig` 에 `notifications/tokens` 없음)과 §3(`git diff --stat develop -- api/src/main/resources/db/migration` 비어 있음) 확인.

**Checkpoint**: SC-001~SC-004 충족. 커밋.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T017 [P] 지식 위키 `../kbap-agenthub/wiki/notification-member-only.md` 작성 — "알림·동의·토큰 등록은 회원 전용(2026-09-07 기획 확정)", 게스트→회원 동의 병합 규칙 폐기 경위(KB-464 → KB-543), `installation_id` 컬럼 잔존·후속 제거 태스크, `NotificationConsent.installationId` 는 출처 기록으로만 사용. `../kbap-agenthub/INDEX.md` 에 한 줄 추가 후 허브에서 커밋.
- [ ] T018 [P] Swagger 실확인 — `./gradlew :api:bootRun` 후 `/swagger-ui/index.html` 그룹 1.1 의 `PUT /api/notifications/tokens` 에 `settings`·`MarketingSettingsRequest`·"게스트" 문구가 없고 "회원 전용" 이 보이는지 확인(quickstart §4, 워크트리 실행 시 `worktree-bootrun-env` 레시피).
- [ ] T019 후속 연결(코드 밖, 사용자 확인 후) — `installation_id` 컬럼 제거 Jira 태스크 등록 후 KB-543 에 코멘트로 링크(`create-jira-task` 스킬), FE 에 contracts/notification-token.md "FE 전달 사항"(로그인 후 등록·`settings` 제거) 공유(SC-005).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup·Foundational**: 없음 — 바로 US1 시작.
- **US1 (Phase 3)**: 선행 없음. MVP.
- **US2 (Phase 4)**: US1 뒤 — 같은 `NotificationTokenService`·테스트 헬퍼(`registerToken` 회원 필수)를 US1 결과 위에서 고친다. T006 이후 `AuthNotificationLinkTest` 가 깨져 있는 상태를 US2 가 해소한다.
- **US3 (Phase 5)**: US1·US2 뒤 — `grantForInstallation`·`claim`·`findOpenGuestByInstallationId` 의 마지막 호출자가 US1(T005)·US2(T008)에서 사라져야 삭제가 컴파일된다.
- **Polish (Phase 6)**: US3 뒤.

### Within Each User Story

- 테스트 재편(T001·T007·T010/T011) → Red 확인 → 구현 삭제 → Green.
- US1 안에서 T002·T003 은 병렬, T004 는 T003 뒤(요청 DTO 필드 제거 → 컨트롤러 인자 제거), T005 는 T004 뒤(서비스 시그니처).
- US3 안에서 T010~T013 은 서로 다른 파일이라 병렬, T014 는 T012 뒤(리포지토리 메서드 삭제 → 서비스 호출 삭제), T015 는 독립.

### Parallel Opportunities

- US1: T002 ∥ T003
- US3: T010 ∥ T011 ∥ T012 ∥ T013 ∥ T015
- Polish: T017 ∥ T018

---

## Parallel Example: User Story 3

```bash
# 서로 다른 파일 — 함께 진행:
Task: "NotificationConsentJpaRepositoryTest 게스트 given 삭제"        # T010
Task: "NotificationJpaRepositoryTest 게스트 given 삭제"               # T011
Task: "NotificationConsent.claim·grantForInstallation + 리포지토리 findOpenGuestByInstallationId 삭제"  # T012
Task: "Notification.forInstallation·installationId 삭제"              # T013
Task: "NotificationTokenApi Swagger 서술 교체"                        # T015
# 그 다음:
Task: "NotificationConsentService grant/revokeForInstallation 삭제"  # T014
Task: "./gradlew build + quickstart grep"                             # T016
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. T001 Red → T002~T005 → T006 Green. 비회원 401 + `settings` 제거만으로 새 비회원 데이터 유입이 멈춘다(SC-001).
2. 이 시점에 `AuthNotificationLinkTest` 가 깨져 있으므로 **US1 단독 배포는 하지 않고** US2 까지 한 PR 에 담는다.

### Incremental Delivery

1. US1 + US2 → 비회원 경로 입구·병합 폐쇄, 전 api 테스트 Green → 커밋.
2. US3 → 죽은 코드·문서 정리, `./gradlew build` Green → 커밋.
3. Polish → 위키·Swagger 실확인·후속 Jira/FE 공유.
4. `open-draft-pr-to-develop` 스킬로 draft PR.

---

## Notes

- 신규 파일 0, 마이그레이션 0(FR-006). 삭제 클래스 1(`MarketingSettingsRequest`).
- Kotlin 소스 주석 금지 — 삭제 근거는 커밋 메시지·research.md·위키에 남긴다.
- `AuthMemberIdOrNull` 애너테이션 자체는 home·food·review·community 컨트롤러가 계속 쓴다 — 삭제하지 않는다.
- `NotificationDevice.register(..., memberId: Long? = null)` 시그니처는 손대지 않는다(로그아웃 상태 표현에 nullable 이 필요).
- 부가 방어(컨트롤러 null 검사·잔존 게스트 행 일괄 철회·400 거부) 넣지 않는다 — research R1·R2·R3.
- 각 Checkpoint 마다 커밋(`commit-after-task`).
