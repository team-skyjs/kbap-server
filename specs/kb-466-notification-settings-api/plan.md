# Implementation Plan: 회원 알림 설정 API — 활동 푸시·K-Bap 소식 두 그룹 재편

**Branch**: `kb-466-notification-settings-api` | **Date**: 2026-09-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-466-notification-settings-api/spec.md`

## Summary

회원 알림 설정을 서버 정본으로 읽고 쓰는 `GET/PATCH /api/notifications/settings`(무버전 매핑, 1.0 부터) 를 만들고, 설정 구조를 **활동 푸시(활동/소식 토글 1)** 와 **K-Bap에서 보내는 소식(두 동의로 켜짐 + 식사 시간 알림 토글 1)** 두 그룹으로 재편한다. 저장 구조는 `notification_setting` 컬럼 교체(`helpful`·`review_reminder` → `activity`·`meal_time`)와 `notification_consent` 에 종류 축(`consent_type`) 추가로 맞추고, 동의 grant/revoke 규칙을 `NotificationConsentService` 하나로 모아 회원(설정 API)·게스트(KB-465 토큰 API)가 공유한다. "K-Bap 소식 켜짐" 은 저장 컬럼이 아니라 두 종류의 열린 동의 존재로 판정한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc, jjwt(기존 인증), Kotest BehaviorSpec + kotest-extensions-spring

**Storage**: MySQL(Flyway, `:api` owner). 변경 테이블 `notification_setting`·`notification_consent`

**Testing**: Kotest BehaviorSpec. api 통합은 `@IntegrationTest`(MySQL Testcontainers + Flyway on + `ddl-auto=validate`), common 리포지토리는 `@SpringBootTest + @Import(MySqlContainerConfig)`

**Target Platform**: `:api` bootJar (ECS dev/prod)

**Project Type**: web-service (모듈러 모놀리스 `:common`·`:api`·`:batch`)

**Performance Goals**: 설정 화면 단건 조회·수정 — 회원당 행 수가 한 자리라 성능 이슈 없음

**Constraints**: KB-464 마이그레이션 파일 수정 금지(새 파일). `PUT /notifications/tokens` 의 인증 선택 경로가 깨지지 않게 JWT 패턴을 정확 경로로 등록. 앱 연동 전이라 KB-465 게스트 계약은 호환 유지 없이 교체

**Scale/Scope**: 신규 엔드포인트 2, 엔티티 2 변경, enum 1 추가, 마이그레이션 1, 서비스 2(설정·동의 공유), KB-465 서비스·DTO·테스트 갱신, ErrorCode 1

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | 통합 테스트(`NotificationSettingControllerTest`)를 먼저 Red 로 두고 구현. 기존 토큰·리포지토리 테스트는 계약 변경분을 먼저 고쳐 Red 확인 |
| II. Bounded Contexts | PASS | `common.domain.notification` 안에서만 엔티티·enum 변경. 크로스 도메인 참조는 `memberId: Long` 뿐(`MemberService.getMember` 로 활성 회원 검증은 KB-465 와 동일 패턴) |
| III. Layered Dependency Direction | PASS | `api.notification.*` → `common.domain.notification`. 어댑터·포트 없음. 컨트롤러는 Spring 애너테이션, `*Api` 인터페이스는 swagger 만 |
| IV. Persistence Ownership | PASS | 엔티티=도메인 모델(`updateActivity`·`updateMealTime`·`grantForMember(type…)`), 리포지토리 public, 서비스 public 메서드 전부 명시 `@Transactional`, 새 Flyway 파일이 스키마 owner. `NotificationConsent`·`NotificationDevice` 에 `delete()` 호출 없음 |
| V. Language Policy | N/A | 언어 파라미터 없음. 요청 검증은 DTO(요청 경계)가 소유 |
| 응답·경로 규약 | PASS | `ResponseEntity<BaseResponse<T>>`, `ApiPaths.API + "/notifications/settings"`, 무버전 매핑(신규 API), `WebConfig` 보호 경로 등록, 에러 코드 `NOTIFICATION-001` 채번 |
| Kotlin 주석 금지 | PASS | 신규 코드 무주석. 근거는 research.md·커밋 메시지 |

Post-design 재검토(Phase 1 후): 위반 없음. Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-466-notification-settings-api/
├── plan.md
├── research.md          # R1~R13 결정
├── data-model.md        # 두 테이블 변경·판정 규칙·사건 표·마이그레이션 SQL
├── quickstart.md        # 검증 순서·수동 시나리오
├── contracts/
│   └── notification-settings-api.md   # GET/PATCH 계약 + KB-465 토큰 계약 변경분
└── tasks.md             # /speckit-tasks 산출
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/notification/
├── model/NotificationSetting.kt            # 변경: helpful·reviewReminder → activity·mealTime
├── model/NotificationPreferences.kt        # 삭제 (소비자 없음)
├── model/NotificationConsent.kt            # 변경: consentType 필드 + companion 시그니처
├── model/NotificationConsentType.kt        # 신규: MARKETING_PRIVACY·MARKETING_RECEIVE
├── NotificationSettingJpaRepository.kt     # 무변경
└── NotificationConsentJpaRepository.kt     # 무변경
common/src/test/kotlin/com/kbap/common/domain/notification/
├── NotificationSettingJpaRepositoryTest.kt # 갱신
└── NotificationConsentJpaRepositoryTest.kt # 갱신(consentType)
common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt   # NOTIFICATION-001 추가

api/src/main/kotlin/com/kbap/api/notification/
├── NotificationSettingController.kt        # 신규: GET/PATCH /settings (무버전)
├── NotificationSettingApi.kt               # 신규: swagger 인터페이스
├── NotificationSettingsResponse.kt         # 신규: activity·news{enabled,mealTime,privacyConsent,receiveConsent}
├── NotificationSettingsUpdateRequest.kt    # 신규: 부분 수정 DTO + AssertTrue(켜기 시 두 버전)
├── NotificationSettingService.kt           # 신규: getSettings·updateSettings
├── NotificationConsentService.kt           # 신규: 종류·버전 규칙 grant/revoke (회원·게스트 공유)
├── NotificationTokenService.kt             # 변경: 게스트 동의를 ConsentService 위임, linkOnLogin 종류별 인수
├── NotificationTokenRegisterRequest.kt     # 변경: MarketingSettingsRequest 두 버전
└── NotificationTokenApi.kt                 # 변경: 문서 문구
api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt        # 보호 경로 추가
api/src/main/resources/db/migration/
└── V2026.09.07.21.07.25__notification_setting_two_groups.sql    # 신규
api/src/test/kotlin/com/kbap/api/notification/
├── NotificationSettingControllerTest.kt    # 신규: spec 시나리오 전부
└── NotificationTokenControllerTest.kt      # 갱신: 두 버전 계약·종류별 인수
```

**Structure Decision**: 기존 `api.notification` 기능 패키지(KB-465)에 설정 컨트롤러·서비스를 추가한다. 동의 규칙은 `NotificationConsentService` 로 분리해 토큰·설정 두 유스케이스가 공유한다(research R6). 엔티티·enum 변경은 `common.domain.notification` 에 국한되고 배치 소비자는 아직 없다.

## Phase 0 — Research (완료)

research.md 의 R1~R13. 핵심: URL·버전(R1), JWT 정확 경로(R2), 선호 컬럼 교체 + `meal_time` 기본 TRUE 로 tri-state 회피(R3), `consent_type` 추가(R4), 켜짐 판정 = 두 종류 열린 동의(R5), 공유 동의 서비스(R6), 중첩 부분 수정 계약(R7), 게스트 계약 조정·종류별 인수(R8), `NOTIFICATION-001`(R9), 새 마이그레이션(R11), `NotificationType` 유지(R12).

## Phase 1 — Design (완료)

- data-model.md: 두 테이블 변경 표, 판정 규칙, 종류 축 반영 사건 표, 유형↔토글 대응, 마이그레이션 SQL.
- contracts/notification-settings-api.md: GET/PATCH 요청·응답·오류·예시, KB-465 토큰 계약 변경분.
- quickstart.md: 테스트 명령·dev 수동 시나리오·원장 확인 SQL.
- `CLAUDE.md` 는 수정하지 않는다(고정 문구). 플랜 발견 경로는 `.specify/feature.json` → 이 파일.

## Phase 2 — Task Breakdown 방향 (tasks.md 는 /speckit-tasks)

1. 마이그레이션 + 엔티티·enum·값 객체 변경 + common 리포지토리 테스트 갱신 (Foundational — ddl validate 통과가 전제).
2. `NotificationConsentService` 신설 + KB-465 토큰 서비스·DTO·테스트를 두 종류 계약으로 이전 (Foundational — 설정 API 가 이 서비스를 쓴다).
3. US1 조회: 응답 DTO·서비스 `getSettings`·컨트롤러 GET·WebConfig·통합 테스트.
4. US2 토글 수정: 요청 DTO·`updateSettings`(activity·mealTime)·PATCH·`NOTIFICATION-001`·테스트.
5. US3 동의 켜기/끄기: `updateSettings` 의 `news.enabled` 경로·재동의·보존·테스트.
6. Polish: swagger 문구, Jira KB-465/466 본문·FE 공유, 지식 위키(두 그룹 재편 결정) 기록.

## Complexity Tracking

해당 없음.
