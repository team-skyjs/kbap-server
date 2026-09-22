# Implementation Plan: 비회원 광고 알림 동의 제거 — 알림 대상을 회원 전용으로

**Branch**: `kb-543-member-only-notification` | **Date**: 2026-09-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-543-member-only-notification/spec.md`

## Summary

푸시 토큰 등록(`PUT /api/notifications/tokens`)을 회원 전용으로 닫고, 토큰 등록 계약에서 게스트 동의(`settings`)를 제거하며, 로그인 시 게스트 동의→회원 동의 병합(`claim`/`revoke`)을 없앤다. 설치 id 만으로 동의·알림을 만들거나 조회하는 코드 진입점(`grantForInstallation`·`revokeForInstallation`·`findOpenGuestByInstallationId`·`NotificationConsent.claim`·`Notification.forInstallation`)을 전부 삭제한다. **스키마·마이그레이션은 손대지 않는다** — `installation_id` 컬럼은 남고, 회원 동의의 "동의 받은 기기" 출처 기록(KB-466)으로는 계속 쓴다. 순수 삭제 작업이라 새 파일·새 추상화는 없다.

기술 접근은 세 겹이다: (1) 인증 경계 — `WebConfig` 의 `GuestExemption(PUT /api/notifications/tokens)` 한 줄 제거로 JWT 필터가 기존 `/api/notifications/*` 패턴을 그대로 적용해 비회원을 401 로 거부, 컨트롤러는 `@AuthMemberIdOrNull Long?` → `@AuthMemberId Long`. (2) 계약 — `NotificationTokenRegisterRequest.settings` 와 `MarketingSettingsRequest` 삭제(모르는 JSON 필드는 Boot 기본 Jackson 설정이 무시), Swagger 서술을 회원 전용으로 교체. (3) 서비스·도메인 — 토큰 서비스에서 게스트 동의 적용·병합 로직 삭제, 동의 서비스·리포지토리·엔티티의 설치 id 전용 메서드 삭제. 테스트는 게스트 시나리오를 401·무변화 시나리오로 바꾸고, 병합 시나리오는 "로그인해도 원장 불변" 으로 대체한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1(web·validation·data-jpa), springdoc-openapi, jjwt(자체 JWT — `JwtAuthenticationFilter`·`AuthMemberId` 리졸버 기존 부품 재사용)

**Storage**: MySQL(Flyway — **이번 변경에 마이그레이션 없음**, FR-006). `notification_consent.installation_id`·`notification.installation_id` 컬럼 유지

**Testing**: Kotest BehaviorSpec + JUnit 5 플랫폼. api 통합 테스트는 `@IntegrationTest`(MockMvc + MySQL·Redis Testcontainers), common 리포지토리 테스트는 `@SpringBootTest` + `MySqlContainerConfig`

**Target Platform**: Linux 서버(api bootJar). batch 는 알림 코드를 소비하지 않아 무영향

**Project Type**: web-service(Gradle 멀티모듈 모듈러 모놀리스 — `:common`·`:api` 만 변경)

**Performance Goals**: N/A(경로 삭제·인증 조건 강화 — 트래픽 특성 불변)

**Constraints**: 스키마 무변경(블루/그린 배포 중 구 코드·신 코드 공존, FR-006·SC-004). 부가 방어 기능 추가 금지(`no-extra-hardening-features`). Kotlin 소스 주석 금지

**Scale/Scope**: 변경 파일 약 12개(main 8·test 4), 신규 파일 0, 삭제 클래스 1(`MarketingSettingsRequest`)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 각 태스크는 실패 테스트 먼저: 비회원 등록 401·기기 행 없음, 회원 요청의 `settings` 무시·원장 불변, 로그인 시 잔존 게스트 동의 행 불변(병합 없음)을 Red 로 확인한 뒤 삭제 구현으로 Green. 삭제되는 게스트 시나리오 테스트는 컴파일 실패로 Red 가 드러난다 |
| II. Bounded Contexts | PASS | 변경은 `common.domain.notification`(엔티티·리포지토리)과 `api.notification`·`api.auth`·`api.core.config` 안에 머문다. 도메인 간 의존 방향 변경 없음(`ModuleBoundaryTest` 허용 맵 무수정) |
| III. Layered Dependency Direction | PASS | api → common 방향 유지. 새 seam·어댑터 없음 |
| IV. Persistence Ownership | PASS | 엔티티 도메인 메서드(`claim`·`grantForInstallation`·`forInstallation`) 삭제만 있고 새 도메인 로직은 없다. 리포지토리 public 유지, 트랜잭션 경계는 기존 `@Transactional` 그대로. JPA 연관관계 없음. 스키마 무변경 |
| V. Domain Content Language Policy | N/A | 음식 콘텐츠·`lang` 폴백과 무관(기기 `lang` 은 검증 없이 저장하는 기존 동작 유지) |

**추가 제약 검토**: 외부 호출 없음. 도메인 모델을 응답으로 노출하지 않음(응답 페이로드 `Unit`). API 응답 봉투 `BaseResponse`·경로 `ApiPaths.API` 규약 유지. `WebConfig` 보호 경로는 이미 `/api/notifications/*` 로 등록돼 있어 추가 등록 불필요.

**Gate 결과: PASS (Phase 0 진입).**

## Project Structure

### Documentation (this feature)

```text
specs/kb-543-member-only-notification/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정 5건
├── data-model.md        # Phase 1 — 엔티티 변경(삭제 메서드·유지 컬럼)
├── quickstart.md        # Phase 1 — 검증 절차(테스트·grep·Swagger)
├── contracts/
│   └── notification-token.md   # PUT /api/notifications/tokens 변경 전후 계약
└── tasks.md             # Phase 2 — /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── core/config/WebConfig.kt                        # GuestExemption(PUT /notifications/tokens) 제거
├── notification/
│   ├── NotificationTokenApi.kt                     # memberId: Long, 회원 전용 Swagger 서술
│   ├── NotificationTokenController.kt              # @AuthMemberId Long, settings 인자 제거
│   ├── NotificationTokenRegisterRequest.kt         # settings 필드·MarketingSettingsRequest 삭제
│   ├── NotificationTokenService.kt                 # registerToken(memberId: Long), linkOnLogin 병합 제거, consentService 의존 제거
│   ├── NotificationConsentService.kt               # grantForInstallation·revokeForInstallation 삭제
│   └── NotificationSettingsUpdateRequest.kt        # MAX_CONSENT_VERSION 상수 이곳으로 이동
api/src/test/kotlin/com/kbap/api/
├── notification/NotificationTokenControllerTest.kt # 게스트 → 401, 동의 given 블록 삭제, 회원 settings 무시 시나리오
└── auth/AuthNotificationLinkTest.kt                # 병합 시나리오 → 원장 불변, 등록은 로그인 후

common/src/main/kotlin/com/kbap/common/domain/notification/
├── NotificationConsentJpaRepository.kt             # findOpenGuestByInstallationId 삭제
└── model/
    ├── NotificationConsent.kt                      # claim·grantForInstallation 삭제(installationId 필드 유지)
    └── Notification.kt                             # forInstallation 삭제, installationId 필드 삭제(컬럼은 유지)
common/src/test/kotlin/com/kbap/common/domain/notification/
├── NotificationConsentJpaRepositoryTest.kt         # "게스트 동의와 로그인 인수" 블록 삭제
└── NotificationJpaRepositoryTest.kt                # "게스트 기기 대상 알림" 블록 삭제
```

**Structure Decision**: 기존 기능 패키지 `com.kbap.api.notification` 과 도메인 패키지 `com.kbap.common.domain.notification` 안에서 삭제·축소만 수행한다. 새 파일·새 패키지·마이그레이션은 만들지 않는다.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.

## Post-Design Constitution Check

Phase 1 산출물(data-model·contracts) 기준 재검토: 엔티티 변경은 메서드·미사용 필드 삭제뿐이고 스키마 무변경(원칙 IV), 계약 변경은 단일 엔드포인트의 인증 조건·요청 필드 축소로 봉투·경로 규약 유지, 테스트는 BehaviorSpec 회귀 유지(원칙 I). **PASS.**

## 산출물 밖 후속 (플랜 범위 밖, 기록용)

- `installation_id` 컬럼 제거는 별도 Jira 태스크로 등록해 KB-543 코멘트로 연결한다(spec Assumptions — 블루/그린 리비전 공존 때문에 이번에 하지 않음).
- FE 에 "로그인 후 토큰 등록" 계약 변경 공유(SC-005). 서버 측 호환 처리 없음 — 구버전 앱의 비회원 등록은 401.
- 지식 위키(`../kbap-agenthub`)에 "알림은 회원 전용" 확정(2026-09-07 기획)과 병합 규칙 폐기 결정을 한 문서로 남긴다.
