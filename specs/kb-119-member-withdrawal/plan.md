# Implementation Plan: 회원 탈퇴 — DB 소프트 삭제와 Firebase user record 삭제

**Branch**: `kb-119-member-withdrawal` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-119-member-withdrawal/spec.md`

## Summary

> **구현 중 설계 변경 2건**(사용자 확정): ① 소셜 ID 토큰 재인증 폐기 — 앱이 탈퇴 직전 재로그인을 하지 않으므로 **서버가 저장된 소셜 신원으로 인증 제공자에 역조회**해 삭제한다(요청 본문 없음, US3·403 폐기). ② 이메일 유지 — `provider_uid` 만 더미 치환한다. 아래 본문은 최종 구현 기준이다.

로그인한 회원이 `PATCH /api/v1/auth/withdraw`(본문 없음)를 호출하면, 서버는 (1) 접근 토큰이 가리키는 회원을 조회하고, (2) 그 회원의 `(provider, providerUserId)` 로 `FirebaseAuth.getUserByProviderUid` → `deleteUser` 를 호출해 **Firebase 사용자 기록을 먼저 삭제**한 뒤, (3) 회원 행을 소프트 삭제하며 `provider_uid` 를 `DELETED:{id}` 로 치환한다. Firebase 삭제가 실패하면 DB 는 그대로 두고 500 + ERROR 로그를 남겨 관리자가 콘솔에서 수동 삭제한다.

기술적 요점 세 가지:
- **Firebase local uid 는 저장하지 않는다.** 이미 저장한 `(provider, provider_uid)` 로 역조회해 uid 를 얻는다 — 스키마·클라이언트 변경 0(research R1).
- **갱신 토큰 무효화는 Redis 구조를 바꾸지 않는다.** `RefreshUseCase` 가 재발급 직전 회원 존재를 확인하면 모든 기기의 잔여 토큰이 한 번에 죽는다(research R4).
- **마이그레이션·도메인·`MemberRepository` port·영속 코드 전부 무변경.** `withdraw` port·adapter·`MemberJpaEntity.withdraw()` 는 KB-117 에서 이미 구현돼 **호출자만 없던 상태**였다(research R5).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa·data-redis), firebase-admin 9.4.3, jjwt, springdoc-openapi

**Storage**: MySQL 8.4 (`member` 테이블 — 스키마 변경 없음) + Redis (갱신 토큰 — 구조 변경 없음)

**Testing**: Kotest `BehaviorSpec` + JUnit5 platform. 단위는 손으로 쓴 페이크, 통합은 MySQL·Redis Testcontainers + MockMvc

**Target Platform**: Linux 서버 (`:app:api` bootJar)

**Project Type**: 모듈러 모놀리스 web 서비스 (ADR-0008)

**Performance Goals**: 탈퇴는 저빈도 쓰기 경로 — 외부 호출(Firebase deleteUser) 1회 + DB 쓰기 1회. 재발급 경로에 회원 PK 조회 1회가 추가된다(무시 가능).

**Constraints**: 외부 호출(Firebase)을 DB 트랜잭션 안에서 잡지 않는다(헌법 Additional Constraints) — `WithdrawUseCase` 에 `@Transactional` 을 두지 않고 Firebase 삭제 → 단일 DB 쓰기 순으로 처리한다. 부분 탈퇴(더미 치환됐는데 Firebase 기록은 생존) 상태를 만들지 않는다.

**Scale/Scope**: 소스 8개 파일(신규 3·수정 5), 테스트 4개 파일. Flyway 0건. 영속·도메인 무변경.

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 설계 후 재확인.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| **I. Test-First** | ✅ | `WithdrawUseCaseTest` → Red(`Unresolved reference`) 확인 후 구현. 컨트롤러·refresh 시나리오도 Red 선행. |
| **II. Bounded Contexts** | ✅ | member 컨텍스트 단독. 도메인 모듈 간 직접 의존 추가 없음. `SocialIdentity`·`SocialProvider` 는 이미 member 소유. |
| **III. Layered Dependency** | ⚠️ 기존 완화 승계 | 신규 port(`SocialAccountDeleter`)와 구현(`FirebaseAccountDeleter`)이 **둘 다 `:application:client`** 에 있다. KB-118 에서 firebase-admin 을 `:application:client` 직접 의존으로 둔 의식적 완화를 그대로 잇는다(Complexity Tracking 참조). 의존 **방향**(app → application → core)은 위반 없음. |
| **IV. Persistence Encapsulation** | ✅ | 영속 코드 무변경. application·app:api 는 `MemberRepository` port 로만 접근. |
| **V. Domain Content Language** | N/A | 음식 콘텐츠와 무관. 사용자 노출 문구는 기존 오류 메시지 컨벤션(~습니다)을 따른다. |

**Post-Phase-1 재확인**: 설계 산출물(research·data-model·contracts)이 위 판정을 바꾸지 않는다. 신규 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-119-member-withdrawal/
├── plan.md              # 이 파일
├── spec.md
├── research.md          # Phase 0 — R1~R8 결정
├── data-model.md        # Phase 1 — 스키마 무변경 + 타입 변화
├── quickstart.md        # Phase 1 — 건드리는 파일·테스트·검증 명령
├── contracts/
│   └── withdraw-api.md  # Phase 1 — PATCH /auth/withdraw 계약
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks 가 생성 — 이 명령은 만들지 않음)
```

### Source Code (repository root)

```text
application/client/src/main/kotlin/com/meogo/application/client/
├── auth/
│   ├── SocialAccountDeleter.kt     # 신규 — port delete(provider, providerUserId)
│   ├── FirebaseAccountDeleter.kt   # 신규 — getUserByProviderUid → deleteUser, USER_NOT_FOUND 흡수
│   ├── AuthConfig.kt               # 수정 — socialAccountDeleter 빈, UnavailableSocialAuth 가 두 port 구현
│   ├── AuthErrorCode.kt            # 수정 — SOCIAL_ACCOUNT_DELETE_FAILED(500)
│   └── RefreshUseCase.kt           # 수정 — MemberRepository 주입, 재발급 전 회원 존재 확인
└── member/
    └── WithdrawUseCase.kt          # 신규 — findById → 소셜 계정 삭제 → 소프트 삭제 (@Transactional 없음)

app/api/src/main/kotlin/com/meogo/app/api/
├── auth/AuthApi.kt                 # 수정 — @PatchMapping("/withdraw") + swagger (본문 없음)
├── auth/AuthController.kt          # 수정 — WithdrawUseCase 주입, @AuthMemberId
└── common/auth/WebMvcAuthConfig.kt # 수정 — 인증 필터에 정확 경로 /api/v1/auth/withdraw 추가
```

`SocialTokenVerifier`·`FirebaseTokenVerifier`·`LoginUseCase`·`infra:persistence`·Flyway 는 **무변경**이다.

**Structure Decision**: 기존 모듈 경계를 그대로 쓴다. 신규 모듈·신규 도메인 컨텍스트 없음. 탈퇴는 세션 종료와 함께 다뤄지므로 **인증 API 그룹(`/api/v1/auth`)** 에 둔다. `/auth/*` 는 로그인·재발급이 공개라 인증 필터 밖이므로, **`/auth/withdraw` 정확 경로만** 필터에 등록한다(와일드카드 `/auth/*` 를 넣으면 로그인이 막힌다).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 III — Firebase 삭제 port(`SocialAccountDeleter`)와 그 구현(`FirebaseAccountDeleter`)이 모두 `:application:client` 안에 있다(port 는 `:core:kernel`, 구현은 `:infra:*` 가 정석) | KB-118 에서 firebase-admin 을 `:application:client` 직접 의존으로 두는 완화를 이미 채택했고(`FirebaseTokenVerifier` 가 같은 자리에 산다), 삭제 어댑터만 다른 모듈로 빼면 같은 `FirebaseApp` 빈이 두 모듈에 흩어진다 | 지금 `:infra:firebase-auth` 모듈을 신설해 두 어댑터를 옮기는 것이 정석이나, 이번 태스크의 본질(탈퇴 플로우)과 무관한 모듈 이설·빌드 배선 비용이 붙는다. **소비자가 늘거나(batch·admin) 인증 제공자를 교체할 때 두 어댑터를 함께 승격**하는 것으로 미룬다 |
