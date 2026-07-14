# Tasks: API 요청 흐름 로깅 (API 모니터링)

**Input**: Design documents from `/specs/kb-130-api-request-logging/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/request-logging.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트(Red 확인) → 구현(Green) → Refactor 순서를 강제한다.

**Organization**: 스토리별 그룹핑. 단, 이 기능은 세 스토리가 같은 필터 한 파일 위에 쌓이는 구조라 **스토리 간 병렬 작업은 불가** — US1 → US2 → US3 순차 진행이 기본이다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·선행 미완 의존 없음)
- **[Story]**: US1(상관 키 필터링) / US2(회원 식별) / US3(진입·응답 요약 + 에러 표준화)

## Path Conventions

Gradle 멀티모듈 — 이번 변경 범위는 `app/api/`(주) + `core/`(ErrorCode 1건). 도메인·application·infra·batch 모듈 및 DB(Flyway) 무변경.

---

## Phase 1: Setup (Shared Infrastructure)

**해당 없음** — 신규 의존성 0(Boot 내장 기능만 사용), 스캐폴딩 불필요. 신규 패키지 `com.kbap.app.api.common.logging` 은 첫 파일 생성 시 함께 만든다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**해당 없음** — 모든 스토리를 막는 선행 인프라 없음. US1 이 사실상의 기반(필터 골격)이며 스토리 순서로 해소한다.

---

## Phase 3: User Story 1 - 요청 단위 상관 키로 로그 필터링 (Priority: P1) 🎯 MVP

**Goal**: 모든 `/api/*` 요청에 UUID 상관 키를 부여해 MDC 로 전 로그에 자동 태깅하고, `X-Request-Id` 응답 헤더로 반환하며, 요청 종료 시 컨텍스트를 정리한다. (진입/응답 로그 자체는 US3 — 여기선 키 부여·태깅·정리만.)

**Independent Test**: API 호출 → 응답 헤더에 UUID 존재, 그 요청 중 남은 기존 로그(예: `GlobalExceptionHandler` 에러 로그)의 MDC 에 같은 키 존재, 요청 2건의 키가 서로 다름, 요청 후 MDC 비어 있음.

### Tests for User Story 1 (Red 확인 필수) ⚠️

- [X] T001 [US1] `app/api/src/test/kotlin/com/kbap/app/api/common/logging/RequestLoggingFilterTest.kt` 신규 — Kotest BehaviorSpec + `@SpringBootTest` + `@AutoConfigureMockMvc` + Logback `ListAppender`. 시나리오: (a) 정상 응답에 `X-Request-Id` 헤더(UUID 형식), (b) JWT 필터 401 거절 응답에도 헤더 존재, (c) `GlobalExceptionHandler` 로거에 부착한 ListAppender 로 에러 유발 요청의 캡처 이벤트 `MDCPropertyMap["requestId"]` == 응답 헤더 값, (d) 동일 API 2회 호출 시 서로 다른 키, (e) 요청 처리 후 현재 스레드 재사용 시 MDC 잔존 없음(필터 통과 후 `MDC.get("requestId") == null` 검증용 테스트 엔드포인트 또는 후속 요청으로 검증), (f) `/actuator/health` 응답에는 `X-Request-Id` 없음. 실행해 **전 케이스 Red 확인**: `./gradlew :app:api:test --tests "*.RequestLoggingFilterTest"`

### Implementation for User Story 1

- [X] T002 [US1] `app/api/src/main/kotlin/com/kbap/app/api/common/logging/RequestLoggingFilter.kt` 신규 — `OncePerRequestFilter` 구현: `UUID.randomUUID()` → `MDC.put("requestId", ...)` → 응답 헤더 `X-Request-Id` 세팅 → `chain.doFilter` → `finally { MDC.clear() }`. 같은 파일에 등록 `@Configuration`(`FilterRegistrationBean`, `order = Ordered.HIGHEST_PRECEDENCE`, `addUrlPatterns("/api/*")`) 동거 — 별도 config 파일을 만들지 않는다(소비자 1, 응집 우선). 클라이언트가 보낸 `X-Request-Id` 헤더는 무시(서버 생성 단일 출처, contracts §1). T001 Green 확인.
- [X] T003 [US1] `app/api/src/main/resources/application.yml` — `logging.pattern.correlation: "[%X{requestId:-}][%X{memberId:-}] "` 추가(베이스 프로필 공통, logback xml 무수정 — research D6). local 부팅 로그에서 correlation 자리 표시 확인.

**Checkpoint**: US1 단독 검증 — T001 전 케이스 Green + quickstart §로컬 1·2번 수동 확인 가능 상태.

---

## Phase 4: User Story 2 - 요청자(회원) 식별 (Priority: P2)

**Goal**: 인증된 요청의 모든 로그에 `memberId` 가 MDC 로 태깅되고, 미인증 요청은 `memberId` 없이 정상 동작한다.

**Independent Test**: 유효 토큰으로 인증 API 호출 → 캡처된 로그 이벤트 MDC 에 `memberId` 존재. 비인증 API 호출 → `memberId` 부재·처리 정상.

### Tests for User Story 2 (Red 확인 필수) ⚠️

- [X] T004 [US2] `RequestLoggingFilterTest.kt` 확장 — 시나리오: (a) 유효 JWT 로 인증 필요 API 호출 시 요청 처리 중 캡처된 로그 이벤트의 `MDCPropertyMap["memberId"]` == 토큰의 회원 id, (b) JWT 필터 미적용 공개 API 호출 시 `memberId` 부재 + 200 정상, (c) 인증 요청 종료 후 `memberId` 도 정리됨(US1 의 `MDC.clear()` 일괄 정리 — 후속 비인증 요청 로그에 이전 회원 id 오염 없음). Red 확인.

### Implementation for User Story 2

- [X] T005 [US2] `app/api/src/main/kotlin/com/kbap/app/api/common/auth/JwtAuthenticationFilter.kt` 수정 — `parseAccessToken` 성공 직후 `MDC.put("memberId", parsed.memberId.toString())` 1줄 추가(정리는 바깥 `RequestLoggingFilter` 의 `MDC.clear()` 가 담당 — research D4). T004 Green 확인.

**Checkpoint**: US1+US2 — 상관 키·회원 식별 두 필터 축 완성(FR-005 의 MDC 측 충족).

---

## Phase 5: User Story 3 - 요청 수신·응답 요약 로그 + 에러 상세 표준화 (Priority: P3)

**Goal**: 요청당 진입 로그(메서드·경로+마스킹된 쿼리)와 응답 로그(status·elapsedMs)가 한 쌍으로 남고(에러 응답 포함), 예외는 표준 형식(예외 타입·ErrorCode·status·uri)으로 기록되며 미처리 예외도 `BaseResponse` 봉투(500, `COMMON-003`)로 표준화된다. staging/prod 는 ECS JSON 출력.

**Independent Test**: API 호출 → 같은 requestId 의 진입/응답 로그 쌍 + status·elapsedMs key-value. 예외 유발 → 표준 에러 로그 + 응답 로그 누락 없음. quickstart JSON 모드에서 필드 노출 확인.

### Tests for User Story 3 (Red 확인 필수) ⚠️

- [X] T006 [P] [US3] `app/api/src/test/kotlin/com/kbap/app/api/common/logging/QueryMaskingTest.kt` 신규 — 마스킹 순수 함수 단위 테스트(BehaviorSpec): 목록 빈 Set 이면 쿼리 원문 유지, 목록에 오른 파라미터만 값 `***` 치환(복수 파라미터·값 없는 파라미터·쿼리 없음 케이스). Red 확인.
- [X] T007 [US3] `RequestLoggingFilterTest.kt` 확장 — 시나리오: (a) 정상 요청 시 `RequestLoggingFilter` 로거에서 진입(`--> GET <path?query>`)·응답(`<-- <status> ... (<N>ms)`) 로그 한 쌍 캡처 + 두 이벤트의 MDC requestId 동일, (b) 응답 이벤트 key-value 에 `status`·`elapsedMs` 존재(SLF4J `KeyValuePair` 검증), (c) 예외 발생 요청도 응답 로그 1회 존재(status 4xx/5xx), (d) actuator 경로는 진입/응답 로그 0건. Red 확인.
- [X] T008 [P] [US3] `app/api/src/test/kotlin/com/kbap/app/api/common/GlobalExceptionHandlerTest.kt` 신규(또는 기존 MockMvc 테스트 보강) — 시나리오: (a) `BusinessException`(4xx) 시 WARN 표준 에러 로그에 예외 타입·`errorCode`·`status`·`uri` key-value, (b) 미처리 `RuntimeException` 시 HTTP 500 + `BaseResponse.fail("COMMON-003", ...)` 응답 봉투 + ERROR 로그(스택 포함), (c) 에러 로그의 MDC requestId 가 응답 헤더와 일치. Red 확인. (테스트용 예외 유발 엔드포인트는 테스트 소스셋의 `@RestController` 픽스처로 둔다 — 프로덕션 코드에 테스트용 API 금지.)

### Implementation for User Story 3

- [X] T009 [US3] `core/src/main/kotlin/com/kbap/core/error/ErrorCode.kt` 수정 — 공통 섹션에 `INTERNAL_SERVER_ERROR("COMMON-003", 500, "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요")` 채번(형식·유일성은 기존 `ErrorCodeStatusTest` 가 자동 검증).
- [X] T010 [US3] `RequestLoggingFilter.kt` 확장 — `MASKED_QUERY_PARAMS: Set<String> = emptySet()` + 마스킹 함수(T006 대상), 진입 로그(INFO, method·path+마스킹 쿼리), 응답 로그(INFO, `atInfo().addKeyValue("status", ...).addKeyValue("elapsedMs", ...)`) — 응답 로그는 `finally` 이전·예외 경로 포함 정확히 1회(contracts §4). T006·T007 Green 확인.
- [X] T011 [US3] `app/api/src/main/kotlin/com/kbap/app/api/common/GlobalExceptionHandler.kt` 수정 — 기존 핸들러 로그를 표준 형식(fluent API key-value: 예외 타입·`errorCode`·`status`·`uri`, 4xx WARN / 5xx ERROR+스택)으로 통일하고 catch-all `@ExceptionHandler(Exception::class)` 신설(`COMMON-003`, 500, `BaseResponse` 봉투 — contracts §1·2). `HttpServletRequest` 로 uri 획득. T008 Green 확인.
- [X] T012 [P] [US3] `app/api/src/main/resources/application-staging.yml`·`application-prod.yml` — `logging.structured.format.console: ecs` 추가(research D5). 검증은 T014 quickstart JSON 모드.

**Checkpoint**: 전 스토리 완성 — FR-001~011 전부 충족 가능 상태.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T013 리팩토링 패스 — `RequestLoggingFilter`·`GlobalExceptionHandler` 중복(로그 형식 문자열·key-value 키명) 정리, Kotlin 주석 규약(코드로 표현 불가능한 제약만 — 예: "응답 로그는 예외 경로 포함 1회" 사유) 점검, 테스트 Green 유지.
- [X] T014 quickstart.md 전 절차 수동 검증 — local bootRun 텍스트 로그(correlation 표시·헤더·에러 흐름·actuator 제외) + `--logging.structured.format.console=ecs` JSON 모드에서 `requestId`·`memberId`·`status`·`elapsedMs` 필드 확인(research D5 검증 포인트 — base.xml include 환경에서 structured 적용 확인).
- [X] T015 전체 검증 — `./gradlew build` (전 모듈 테스트 + ArchUnit 포함) 통과 확인.

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup·Foundational: 없음 — 바로 US1 시작.
- **US1 → US2 → US3 순차**: US2 는 US1 의 MDC 정리(`MDC.clear()`)에, US3 은 US1 의 필터 골격에 코드를 얹는다(같은 파일). 스토리 간 병렬 불가.
- Polish: 전 스토리 완료 후.

### Within Each User Story

- Red(테스트 작성 + 실패 확인) → Green(최소 구현) → 체크포인트. 테스트 실패 확인 없이 구현 금지(헌법 I).

### Parallel Opportunities

- US3 내부: T006(마스킹 단위 테스트) ∥ T008(핸들러 테스트) — 서로 다른 파일. T012(yml) ∥ T009~T011.
- 그 외는 같은 파일(`RequestLoggingFilter.kt`·`RequestLoggingFilterTest.kt`)을 잇달아 수정하므로 순차.

## Parallel Example: User Story 3

```bash
# Red 테스트 2건 동시 작성 (서로 다른 파일):
Task: "QueryMaskingTest.kt — 마스킹 순수 함수 단위 테스트"
Task: "GlobalExceptionHandlerTest.kt — 표준 에러 로그·catch-all 테스트"
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. US1 완료(T001~T003) → 상관 키 부여·태깅·헤더·정리 단독 검증 — 이것만으로도 "키 하나로 요청 로그 묶기"가 동작(MVP).
2. US2(T004~T005) → 회원 필터 축 추가.
3. US3(T006~T012) → 흐름 완결(진입/응답·에러 표준화·JSON).
4. Polish(T013~T015) → quickstart·전체 빌드.

### 총 15 tasks — US1: 3, US2: 2, US3: 7, Polish: 3

## Notes

- MDC 키 이름은 `"requestId"`·`"memberId"` 로 고정(yml correlation 패턴·테스트·ECS 필드가 공유하는 계약 — contracts 참조).
- 커밋은 스토리 체크포인트 단위 이상으로 쪼갠다(작업/논리 단위 커밋 — 헌법 Workflow).
- `:app:batch`·도메인 모듈은 어떤 태스크도 건드리지 않는다 — PR 리뷰 시 diff 범위 검증 포인트.
