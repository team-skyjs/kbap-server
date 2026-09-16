# Implementation Plan: Sentry 4xx·클라이언트 끊김 이벤트 미전송

**Branch**: `kb-568-sentry-4xx-drop` | **Date**: 2026-09-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-568-sentry-4xx-drop/spec.md`

## Summary

api 의 Sentry 이벤트 프로세서(`SentryRequestContextProcessor`)가 이미 계산하는 `http.status` 판정을 **전송 직전 drop 판정으로 재사용**한다 — 4xx(낙관적 락 409 제외)와 인바운드 클라이언트 끊김(`ClientAbortException`·`AsyncRequestNotUsableException` 원인 체인)이면 `null` 을 돌려 이벤트를 버리고, 5xx·낙관적 락 409·예외 없는 로그 이벤트는 종전 태그·핑거프린트 그대로 통과시킨다. `exception-resolver-order`·`ignored-exceptions-for-type`·`GlobalExceptionHandler`·HTTP 응답·로그는 무변경. 자동 테스트는 두지 않고(사용자 결정) 로컬 가짜 DSN 의 drop 로그와 dev 실이벤트로 검증하며, `docs/observability/sentry.md` 수집 계약을 갱신한다. 닫힌 PR #257 의 프로세서 diff 를 그대로 되살린다(Codex P1·P2 반영본, 테스트 파일은 제외).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 · `io.sentry:sentry-spring-boot-4-starter` 8.55.0(`EventProcessor.process` 는 `@Nullable` 반환 — drop 계약) · tomcat-embed-core 11.0.22(`ClientAbortException`, web 스타터로 이미 api 컴파일 클래스패스) · spring-web 7.0.8(`AsyncRequestNotUsableException`, `ErrorResponse`, API 버전 예외). 신규 의존 없음

**Storage**: N/A

**Testing**: 자동 테스트 없음 — 사용자 결정(2026-09-09 KB-508, 2026-09-15 KB-568 재확인: Sentry 관측 부품 무테스트). 검증은 로컬 가짜 DSN + `sentry.debug` 의 SDK drop 로그와 dev 콘솔 실이벤트(quickstart). 기존 `:api:test` 전체 통과 유지

**Target Platform**: api 컨테이너(ECS dev → prod). batch 는 무변경(HTTP 경계 없음)

**Project Type**: web-service 관측 부품 수정(Kotlin 1 파일 + 문서 1 파일)

**Performance Goals**: N/A — 전송 이벤트가 줄어든다. 프로세서 추가 비용은 원인 체인 순회 1회(이미 낙관적 락 판정에서 하던 것)

**Constraints**: `exception-resolver-order: -2147483648` 유지(5xx 캡처 경로) · `ignored-exceptions-for-type` 의 `NoResourceFoundException` 유지(PR #258) · 태그 규약·헤더/쿼리 마스킹 무변경 · Kotlin 주석 금지 · 인바운드 끊김은 예외 타입으로만 판정(메시지 매칭 금지)

**Scale/Scope**: 파일 2개 — `api/.../core/observability/SentryRequestContextProcessor.kt`, `docs/observability/sentry.md`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | **예외(정당화)** | 사용자 결정 2026-09-15(KB-568): "테스트 코드는 필요없음" — 2026-09-09 KB-508 의 Sentry 관측 부품 무테스트 결정을 분기 로직에도 그대로 적용. 검증은 SDK drop 로그·dev 실이벤트로 대체. Complexity Tracking 참조 |
| II. Bounded Contexts | 통과 | `com.kbap.api.core.observability` 만 접촉, 도메인 패키지 무접촉 |
| III. Layered Dependency Direction | 통과 | api → common(`BusinessException`·`ErrorCode`) 방향. tomcat 클래스 참조는 `api.core` 소속이라 ArchUnit 경계 규칙 대상 밖(PR #257 에서 `arch` 태그 통과 확인) |
| IV. Persistence Ownership | 통과 | 영속 무접촉 |
| V. Domain Content Language Policy | 통과 | 무관 |

**Post-design re-check**: Phase 1 설계 후 동일 — 원칙 I 예외 외 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-568-sentry-4xx-drop/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — SDK·예외 계층·선행 PR 결말 확인(R1~R9)
├── data-model.md        # Phase 1 — 엔티티 없음(판정 규칙 표)
├── quickstart.md        # Phase 1 — 로컬 가짜 DSN·dev 검증 절차
├── contracts/
│   └── sentry-drop-policy.md   # Phase 1 — 프로세서 판정 계약·문서 변경 계약
└── tasks.md             # Phase 2 — /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/core/observability/SentryRequestContextProcessor.kt   # process 반환 SentryEvent? + drop 판정 (유일한 런타임 변경)
docs/observability/sentry.md                                                            # 수집 계약·"수집되지 않는 것"·노이즈 조정 안내 갱신
```

**Structure Decision**: 기존 프로세서 한 곳에 판정을 모은다 — 리졸버·logback 두 캡처 경로가 모두 이 프로세서를 지나므로 한 번 걸면 양쪽이 같이 걸린다(FR-002·Edge "ERROR 로그 경로"). `application.yml` 은 손대지 않는다: `ignored-exceptions-for-type` 은 프로세서보다 앞에서 더 싸게 버리므로 그대로 두고, `exception-resolver-order` 는 5xx 를 잡는 유일한 수단이라 유지. batch 는 파일 무변경 — 결정만 문서 한 줄.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 I 예외 — 자동 테스트 없음(spec FR-010·SC-006 을 로컬 drop 로그·dev 검증으로 대체) | 사용자 결정 2026-09-15 "테스트 코드는 필요없음"(KB-508 의 관측 무테스트 결정 재확인). 판정은 `GlobalExceptionHandler` 와 동일한 상태 매핑 + 타입 검사라 dev 실이벤트 0건/5xx 1건으로 양방향(4xx 재유입·5xx 은폐) 회귀가 즉시 드러난다 | Spring 없는 단위 테스트(PR #257 의 12 시나리오) — 사용자가 명시적으로 기각. 통합 테스트 — DSN 부재로 자동구성이 뜨지 않아 새 컨텍스트 필요(KB-392 위반) |
