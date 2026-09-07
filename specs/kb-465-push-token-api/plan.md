# Implementation Plan: 푸시 토큰 등록 API 와 로그인·로그아웃·탈퇴 시 기기-회원 연결

**Branch**: `kb-465-push-token-api` | **Date**: 2026-09-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-465-push-token-api/spec.md` (Jira KB-465, 에픽 KB-463 — KB-464 PR #246 위에 쌓임)

## Summary

`PUT /api/notifications/tokens` 하나를 **`X-API-Version 1.1` 이상 전용**으로 연다. 요청의 `X-Installation-Id` 헤더를 유일 키로 `notification_device` 를 upsert(없으면 `register`, 있으면 `renew`) 하고, 회원 인증이 붙었으면 `linkMember`, 게스트면 `settings.marketing` 값을 `notification_consent` 원장에 반영한다(on → 같은 버전 열린 행 없을 때만 `grantForInstallation`, 다른 버전 열린 행은 `revoke`; off → 열린 게스트 행 전부 `revoke`). 기존 인증 API 는 **1.0 매핑을 그대로 두고 같은 경로에 `1.1+` 매핑을 추가**해, 1.1+ 요청에서만 기기-회원 연결 처리를 한다(research R11) — 로그인은 `linkMember` + 게스트 동의 인수(`claim`)/철회 분기, 로그아웃은 `unlinkMember` 만, 탈퇴는 회원의 모든 기기 `unlinkMember` + `closeOpenByMemberId`. 새 코드는 `com.kbap.api.notification` 기능 패키지(`NotificationToken*` 컨트롤러·Api 인터페이스·요청 DTO·서비스) 하나와 `AuthController`/`AuthApi` 의 `1.1+` 매핑 추가, `AuthService` 의 기본 인자 확장뿐이다. 새 엔티티·마이그레이션·ErrorCode·외부 seam 은 없다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, Kotest BehaviorSpec + kotest-extensions-spring. KB-464 가 만든 `common.domain.notification` 엔티티·리포지토리를 그대로 소비

**Storage**: MySQL 8 — `notification_device`·`notification_consent`(KB-464 마이그레이션 `V2026.09.07.15.39.45`). 이 기능은 스키마를 바꾸지 않는다

**Testing**: `:api` 통합 테스트(`@IntegrationTest` + MockMvc, BehaviorSpec). 회원 로그인은 `FakeSocialTokenVerifier`, 상태 검증은 `DataSource` JDBC 직접 조회(기존 `AuthControllerTest` 방식)

**Target Platform**: Linux 컨테이너(ECS EC2), 공유 RDS

**Project Type**: 모듈러 모놀리스 백엔드 — `:api` 기능 패키지 추가 + 인증 흐름 확장

**Performance Goals**: 등록 요청은 유니크 인덱스 단건 조회 + 행 1개 갱신/삽입 + 동의 조회 1회. 로그인 부가 처리는 조회 3회 이내(기기·게스트 동의·회원 동의). 측정 목표 없음(호출 빈도 = 앱 실행당 1회)

**Constraints**: **1.0 인증 API 동작 불변**(기존 `AuthControllerTest` 무수정 Green 이 증거), 토큰 관리는 `1.1+` 에서만, 기기 행 삭제 금지(KB-464 R7), 동의 원장 행 삭제 금지, 회원 요청의 동의 값 무시, 인증 부가 처리 실패가 인증 자체를 깨지 않아야 함(FR-013 — 헤더 없음·미등록 기기), 트랜잭션 경계 명시(`@Transactional`), 외부 호출(소셜 삭제)은 트랜잭션 밖, Kotlin 주석 금지, 응답 봉투·`X-API-Version` 필수 규약

**Scale/Scope**: 신규 파일 5개(`api.notification` 4 + `core.ApiHeaders` 1) · 수정 3개(`AuthController`·`AuthApi` — `1.1+` 매핑 3개 추가, `AuthService` — 기본 인자 추가) · 테스트 2개(등록 API·인증 연동, 둘 다 `X-API-Version: 1.1`) · 기존 `AuthControllerTest`(1.0) 무수정

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 게이트 | 판정 |
|---|---|---|
| I. Test-First | `NotificationTokenControllerTest`(US1·US2)·`AuthNotificationLinkTest`(US3) 를 먼저 작성해 Red(404/컴파일 실패) 확인 후 컨트롤러·서비스로 Green. tasks 에서 테스트 태스크가 구현 태스크에 선행 | PASS |
| II. Bounded Contexts | 도메인 코드 무변경 — `common.domain.notification` 의 엔티티·리포지토리를 `api.notification` 가 소비. 회원은 `Long memberId` 로만 참조. `api.auth` → `api.notification` 참조는 기능 패키지 간 조합이라 도메인 허용 맵 대상 아님(헌법 II 명시) | PASS |
| III. Dependency Direction | 변경 범위가 `:api` 뿐. `api.notification` → `common.domain.notification`·`common.core.error` 방향만. 새 seam·어댑터 없음 | PASS |
| IV. Persistence Ownership | upsert 판단·동의 원장 정책·로그인 인수 분기는 `api.notification.NotificationTokenService`(`@Service`, 메서드별 `@Transactional`)가 소유하고, 엔티티 도메인 메서드(`register`/`renew`/`linkMember`/`unlinkMember`/`grantForInstallation`/`revoke`/`claim`)를 호출한다. 리포지토리는 서비스가 직접 주입(창구 서비스 없음). 상태 변경은 dirty checking(`save` 는 신규 삽입에만) | PASS |
| V. Language Policy | `lang` 은 검증·정규화 없이 저장(KB-464 R3 계승). 입력 검증(토큰 공백·플랫폼 허용값·동의 버전 양의 정수·on 이면 버전 필수)은 요청 DTO(`@field:NotBlank`·`@field:Pattern`·`@field:Positive`·`@get:AssertTrue`)와 헤더 파라미터 제약이 소유하고 서비스는 확정값을 받는다 | PASS |

위반 없음 → Complexity Tracking 비움.

## Project Structure

### Documentation (this feature)

```text
specs/kb-465-push-token-api/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정 11건
├── data-model.md        # Phase 1 — 상태 전이·사건별 원장 조작·트랜잭션 경계
├── contracts/
│   └── notification-tokens.md   # Phase 1 — PUT /api/notifications/tokens 계약 + 인증 API 헤더 확장
├── quickstart.md        # Phase 1 — Red/Green·실기동 검증 절차
└── tasks.md             # /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── core/
│   └── ApiHeaders.kt                       # 신규 — const INSTALLATION_ID = "X-Installation-Id" (notification·auth 공용 단일 출처)
├── notification/                           # 신규 기능 패키지 (도메인 컨텍스트명 미러 — 패키지·클래스·URL 전부 notification)
│   ├── NotificationTokenApi.kt                     # swagger 문서 인터페이스
│   ├── NotificationTokenController.kt              # PUT /api/notifications/tokens (version = "1.1+") — @AuthMemberIdOrNull + @RequestHeader
│   ├── NotificationTokenRegisterRequest.kt         # token·platform·lang·settings{marketing, marketingConsentVersion}
│   └── NotificationTokenService.kt                 # registerToken / linkOnLogin / unlinkOnLogout / closeOnWithdraw
└── auth/
    ├── AuthApi.kt                          # 수정 — 1.1+ 메서드 3개 문서 추가(X-Installation-Id 헤더), 1.0 문서 불변
    ├── AuthController.kt                   # 수정 — 같은 경로에 version = "1.1+" 매핑 3개 추가, 1.0 매핑 불변
    └── AuthService.kt                      # 수정 — login/logout/withdraw 에 기본 인자 추가, 주어졌을 때만 NotificationTokenService 호출

api/src/test/kotlin/com/kbap/api/
├── notification/NotificationTokenControllerTest.kt # 신규 — US1·US2 수용 시나리오 (X-API-Version 1.1)
└── auth/AuthNotificationLinkTest.kt        # 신규 — US3 수용 시나리오 + 1.0 무영향 검증 (AuthControllerTest 비대화 방지)
```

**Structure Decision**: 기능 패키지·클래스 접두는 도메인 컨텍스트명을 미러해 `api.notification` / `NotificationToken*` 로 둔다(research R1 — 사용자 결정). URL 도 `/api/notifications/tokens` 다(Jira 초안 `/api/push/tokens` 대체). KB-466 의 회원 설정 API 도 같은 패키지에 얹는다. 버전은 토큰 API 와 인증 1.1+ 매핑 모두 `1.1+`(research R11). `WebConfig` 의 JWT 보호 경로에는 등록하지 않는다 — 게스트·회원 겸용 엔드포인트의 선례(`/api/home`)를 따라 `@AuthMemberIdOrNull` 리졸버가 토큰을 직접 해석한다(research R2).

## Phase 0 → [research.md](research.md)

## Phase 1 → [data-model.md](data-model.md), [contracts/notification-tokens.md](contracts/notification-tokens.md), [quickstart.md](quickstart.md)

## Post-Design Constitution Re-check

data-model·contracts 확정 후 재검토(2026-09-07 사용자 결정 — 1.1 버전 게이트·notification 접두 반영): 새 영속 객체 없음, 1.0 핸들러·`AuthService` 기본 인자 경로는 무변경, 도메인 메서드 호출만으로 상태 전이(엔티티에 새 메서드 추가 불필요 — KB-464 가 이미 전부 제공), 트랜잭션 경계는 `NotificationTokenService` 메서드 4개가 각자 소유하고 `AuthService` 는 트랜잭션 없이 순서만 조합(소셜 삭제는 밖). 입력 검증은 전부 요청 경계. 원칙 I~V PASS 유지.

## Complexity Tracking

위반 없음.
