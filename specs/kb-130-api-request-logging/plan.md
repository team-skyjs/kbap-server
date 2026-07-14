# Implementation Plan: API 요청 흐름 로깅 (API 모니터링)

**Branch**: `kb-130-api-request-logging` | **Date**: 2026-07-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-130-api-request-logging/spec.md`

## Summary

비즈니스 API(`/api/v*`) 요청마다 UUID 상관 키를 부여해 그 요청의 모든 로그(진입·처리·에러·응답)에 자동 태깅하고, 인증 요청에는 회원 식별자를 함께 남긴다. 구현 축은 셋: (1) 최전방 서블릿 필터 `RequestLoggingFilter` 가 MDC(`requestId`) 세팅·`X-Request-Id` 응답 헤더·진입/응답 요약 로그·`finally` MDC 정리를 담당, (2) `JwtAuthenticationFilter` 가 파싱 성공 시 `MDC.put("memberId")` 한 줄 추가, (3) `GlobalExceptionHandler` 가 에러 로그를 표준 형식(예외 타입·ErrorCode·HTTP 상태·URI)으로 통일하고 catch-all 핸들러를 신설한다. 로그 출력은 local/dev 텍스트(`logging.pattern.correlation`) / staging·prod JSON(Boot 내장 `logging.structured.format.console: ecs`) — **신규 의존성 0, 도메인·application 모듈 무변경, DB 무변경**.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation), SLF4J 2 + Logback(스타터 내장), Boot 내장 structured logging — **신규 라이브러리 없음**

**Storage**: N/A — 로그는 콘솔 스트림(수집기 위임), DB·Flyway 무변경

**Testing**: Kotest BehaviorSpec + JUnit 5 플랫폼, MockMvc(`@SpringBootTest`+`@AutoConfigureMockMvc`, MySQL Testcontainers), Logback `ListAppender` 로그 캡처

**Target Platform**: `:app:api` web bootJar 단독 (`:app:batch` 범위 밖)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 이번 변경은 `:app:api` + `:core`(ErrorCode 1건) 한정

**Performance Goals**: 로깅 오버헤드 체감 불가 수준(SC-005) — 요청당 UUID 1회 + MDC put/clear + 로그 2줄이 전부, I/O 추가 없음

**Constraints**: MDC 는 ThreadLocal — api 요청 경로 전부 동기(스레드 전환 없음) 전제. 로깅 실패가 요청 처리에 전파되지 않아야 함(FR-009). 민감 데이터(토큰·본문) 미기록(FR-008)

**Scale/Scope**: 신규 클래스 1(`RequestLoggingFilter`) + 기존 3파일 수정(`JwtAuthenticationFilter`·`GlobalExceptionHandler`·`ErrorCode`) + yml 3파일 + 테스트 2종

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* — **PASS (Phase 1 이후 재확인 PASS)**

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 마스킹 함수 단위 테스트 + MockMvc 로그 캡처 통합 테스트를 구현보다 먼저 작성(Red 확인) — tasks 에서 강제 |
| II. Bounded Contexts | PASS | 도메인 모듈 무접촉. `:core` 는 `ErrorCode` 채번 1건(공통 에러는 `:core` 소유가 기존 규약) |
| III. Layered Dependency Direction | PASS | 신규 코드는 전부 `:app:api`(web 관심사). 모듈 의존 변경·신규 의존성 없음 |
| IV. Persistence Encapsulation | PASS | 영속 코드 무접촉 |
| V. Domain Content Language Policy | PASS | 음식 콘텐츠 무관 |

## Project Structure

### Documentation (this feature)

```text
specs/kb-130-api-request-logging/
├── plan.md              # This file
├── research.md          # Phase 0 — 결정 9건(D1~D9)
├── data-model.md        # Phase 1 — 로그 레코드 필드 정의(영속 없음)
├── quickstart.md        # Phase 1 — 로컬 검증 절차
├── contracts/
│   └── request-logging.md  # X-Request-Id 헤더 + 로그 이벤트 스키마
└── tasks.md             # Phase 2 (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
app/api/src/main/kotlin/com/kbap/app/api/
├── common/logging/
│   └── RequestLoggingFilter.kt          # 신규 — 상관 키·진입/응답 로그·MDC 정리 (등록 @Bean 포함)
├── common/auth/
│   └── JwtAuthenticationFilter.kt       # 수정 — 파싱 성공 시 MDC.put("memberId") 1줄
├── common/
│   └── GlobalExceptionHandler.kt        # 수정 — 에러 로그 표준화 + catch-all(Exception) 핸들러
app/api/src/main/resources/
├── application.yml                      # 수정 — logging.pattern.correlation
├── application-staging.yml              # 수정 — logging.structured.format.console: ecs
├── application-prod.yml                 # 수정 — logging.structured.format.console: ecs
└── logback-spring.xml                   # 삭제 — base.xml include 가 패턴 인코더를 고정해 structured 포맷을 무력화(research D5 정정)

core/src/main/kotlin/com/kbap/core/error/
└── ErrorCode.kt                         # 수정 — INTERNAL_SERVER_ERROR("COMMON-003", 500) 채번

app/api/src/test/kotlin/com/kbap/app/api/
├── common/logging/
│   ├── QueryMaskingTest.kt              # 신규 — 마스킹 순수 함수 단위 테스트
│   └── RequestLoggingFilterTest.kt      # 신규 — MockMvc + ListAppender 통합 테스트
└── common/
    └── GlobalExceptionHandlerTest.kt    # 신규 또는 기존 보강 — 표준 에러 로그·catch-all 검증
```

**Structure Decision**: 로깅은 web 횡단 관심사이므로 전부 `:app:api` 의 `common/logging/` 에 응집한다. 도메인·application·infra 모듈은 파일 하나도 바뀌지 않는다(유일 예외: `:core` 의 `ErrorCode` 상수 1건 — 공통 에러 코드의 기존 소유처). 필터 등록은 별도 `@Configuration` 을 만들지 않고 `RequestLoggingFilter` 파일 안 companion/등록 빈으로 최소화하되, 기존 `WebMvcAuthConfig` 패턴과 어긋나면 `config/` 에 등록 빈만 둔다 — tasks 에서 확정.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.
