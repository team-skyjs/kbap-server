# Implementation Plan: Sentry 연동 — api·batch 컨테이너 에러 로그 수집

**Branch**: `kb-508-sentry-error-alerting` | **Date**: 2026-09-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-508-sentry-error-alerting/spec.md`

## Summary

api·batch 두 부트앱에 Sentry SDK(Spring Boot 4 스타터 + logback)를 붙여 **핸들러 예외(4xx·5xx 전부)와 ERROR 로그·배치 잡 실패**를 이벤트로 수집한다. 전역 어드바이스가 모든 예외를 삼키는 구조는 `sentry.exception-resolver-order = HIGHEST_PRECEDENCE` 한 줄로 우회하고(어드바이스 무수정), `EventProcessor` 빈 하나가 MDC(requestId·memberId)와 `BusinessException` 의 status·code 를 태그로 승격하며 에러 코드별 핑거프린트로 이슈를 묶는다. 컨테이너 구분은 **Sentry 프로젝트 2개(api/batch, DSN 2개)** + SDK 기본 `server_name`(컨테이너 id) 두 층. release 는 배포 워크플로가 이미지 태그를 `SENTRY_RELEASE` 로 주입한다. 알림 규칙은 범위 밖(콘솔).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21

**Primary Dependencies**: `io.sentry:sentry-spring-boot-4-jakarta`, `io.sentry:sentry-logback` (Sentry Java 8.x, 버전 카탈로그) — Boot 4 모듈 가용성은 구현 첫 태스크에서 확인, 폴백 경로 research R2

**Storage**: 없음(앱 DB 변경 없음). 비밀값은 SSM SecureString 2개 × env

**Testing**: Kotest BehaviorSpec, Spring 없는 단위 테스트 3개(프로세서·yml 설정 고정·잡 리스너). 통합 컨텍스트 무변경(DSN 부재 = Sentry 빈 미등록)

**Target Platform**: ECS dev/prod (api·batch 태스크)

**Project Type**: web-service + batch (모듈러 모놀리스)

**Performance Goals**: 전송 비동기·큐 상한(SDK 기본). 요청 지연 영향 없음

**Constraints**: `GlobalExceptionHandler`·`RequestLoggingFilter` 무수정. `@IntegrationTest` 단일 컨텍스트 규약(프로퍼티 추가 금지). SSM 파라미터 없으면 ECS 태스크 기동 거부 → 배포 순서 강제. Kotlin 주석 금지

**Scale/Scope**: 의존성 2 + yml 2 + Kotlin 클래스 2(프로세서·잡 리스너) + 잡 빌더 2곳 `.listener` + 테스트 3 + terraform 변수 기본값 2 + 워크플로 jq 4 + 문서 1

## Constitution Check

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | 프로세서·yml 고정·리스너 테스트를 Red 로 먼저 두고 구현. E2E 는 dev 수동(quickstart) |
| II. Bounded Contexts | PASS | 도메인 코드 무변경. 관측 부품은 `api.core.observability`·`batch.observability` |
| III. Layered Dependency Direction | PASS | Sentry SDK 는 api·batch 의 프레임워크 의존(actuator·micrometer 와 같은 층). `:common` 은 모른다 |
| IV. Persistence Ownership | N/A | 영속 변경 없음 |
| V. Language Policy | N/A | |
| 응답·경로 규약 | PASS | 엔드포인트 변경 없음. 어드바이스 응답 그대로 |
| Kotlin 주석 금지 | PASS | 근거는 research·문서·커밋 메시지 |
| ArchUnit(어댑터 참조) | PASS | Sentry 는 `common.port` seam 이 아니다 — 로깅 프레임워크처럼 앱 전역 관측 인프라라 포트를 두지 않는다(micrometer 선례). `ModuleBoundaryTest` 에 걸리는 `common.infra`/`api.infra` 패키지를 쓰지 않는다 |

Post-design 재검토: 위반 없음. Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-508-sentry-error-alerting/
├── plan.md
├── research.md          # R1~R11
├── data-model.md        # 이벤트 태그 스키마
├── contracts/sentry-config.md   # env·yml·워크플로 jq·terraform·콘솔 계약
├── quickstart.md        # 배포 순서·dev 시나리오
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
gradle/libs.versions.toml                 # sentry 버전 + 좌표 2개
api/build.gradle.kts                      # sentry starter + logback
batch/build.gradle.kts                    # sentry starter + logback
api/src/main/resources/application.yml    # sentry.* (dsn·environment·release·resolver-order·tags·logging)
batch/src/main/resources/application.yml  # sentry.* (dsn·environment·release·tags·logging)
api/src/main/kotlin/com/kbap/api/core/observability/
└── SentryRequestContextProcessor.kt      # 신규: MDC → 태그, BusinessException → status·code·fingerprint
api/src/test/kotlin/com/kbap/api/core/observability/
├── SentryRequestContextProcessorTest.kt  # 신규
└── SentryConfigTest.kt                   # 신규: application.yml 의 sentry.* 고정
batch/src/main/kotlin/com/kbap/batch/observability/
└── JobNameMdcListener.kt                 # 신규
batch/src/main/kotlin/com/kbap/batch/{outbox,vector}/…JobConfig.kt   # .listener(jobNameMdcListener)
batch/src/test/kotlin/com/kbap/batch/observability/
└── JobNameMdcListenerTest.kt             # 신규
iac/terraform/modules/ecs-environment/variables.tf   # api/batch_secret_names 기본값
.github/workflows/deploy-{dev,prod,batch-dev,batch-prod}.yml   # jq: SENTRY_RELEASE
docs/observability/sentry.md              # 신규
```

**Structure Decision**: 관측 부품은 기능 패키지가 아니라 `api.core.observability`·`batch.observability` 에 둔다(`core.logging` 의 `RequestLoggingFilter` 와 같은 층). `:common` 은 Sentry 를 모르고, 포트·어댑터 패턴을 쓰지 않는다(research/헌법 표 참조).

## Phase 0 — Research (완료)

research.md R1~R11. 핵심: 리졸버 순서로 어드바이스 앞 캡처(R1), Boot 4 스타터 + 폴백(R2), 프로세서 하나로 태그·핑거프린트(R3), 워크플로 jq 로 release(R4), 프로젝트·DSN 2개 + SSM 이름 분리(R5), DSN 부재 = 비활성(R6), ERROR 이벤트·INFO breadcrumb(R7), PII 비전송(R8), 잡 리스너 MDC(R9), Spring 없는 단위 테스트 3개(R10).

## Phase 1 — Design (완료)

- data-model.md: 공통·api·batch 태그 표, 핑거프린트 규칙, 제외 항목.
- contracts/sentry-config.md: 환경변수·yml·jq·terraform·콘솔·코드 계약.
- quickstart.md: SSM → terraform → 배포 순서, dev 시나리오 6개, 되돌리기.
- `CLAUDE.md` 무수정.

## Phase 2 — Task Breakdown 방향

1. Setup: 카탈로그·의존성·Boot 4 모듈 가용성 확인(컴파일).
2. Foundational: yml 두 벌 + `SentryConfigTest` Red→Green.
3. US1(api 오류 응답): `SentryRequestContextProcessorTest` Red → 프로세서 구현 → Green.
4. US2(로그·배치): `JobNameMdcListenerTest` Red → 리스너 + 잡 빌더 등록 → Green.
5. US3(프로젝트·비밀값·release): terraform 변수, 워크플로 jq 4개, 문서.
6. Polish: 전체 빌드, 문서, Jira KB-508 본문 갱신(4xx 포함·알림 범위 밖), dev 배포 검증(quickstart).

## Complexity Tracking

해당 없음.
