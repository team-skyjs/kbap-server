# Tasks: 스캔 rate-limit 을 "인식 실패"와 분리

**Input**: `/specs/kb-394-scan-rate-limit/` (plan · research · data-model · contracts/scan-rate-limit-error.md · quickstart) · Jira KB-394

**Tests**: Test-First (헌법 I). Red 는 (1) 새 포트 예외·에러코드를 참조하는 어댑터 테스트의 컴파일 실패 → 타입 추가 → (2) 어댑터 테스트 실패(아직 SDK 예외를 뭉갬) → 루프 구현 → (3) 컨트롤러 테스트 실패 → ScanService 매핑. 테스트 스타일은 BehaviorSpec·한국어 given/when/then, Kotlin 주석 금지.

**Organization**: Foundational(타입) → US1 코드·UX(P1) → US2 로그(P1) → US3 재시도 예산(P2) → US4 Retry-After payload(P3) → Polish. 어댑터 루프는 US1 에서 즉시 갈래(quota·x-should-retry:false)까지, US3 에서 예산 재시도로 확장한다.

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

- common: `common/src/main/kotlin/com/kbap/common/` (`core/error`, `port/llm`, `infra/llm/{menu,config}`), 테스트 `common/src/test/kotlin/com/kbap/common/infra/llm/menu/`
- api: `api/src/main/kotlin/com/kbap/api/scan/`, 테스트 `api/src/test/kotlin/com/kbap/api/{scan,openapi}/`
- 워크트리 `.claude/worktrees/kb-394-scan-rate-limit` 에서 실행. 격리 훅이 복합 셸을 거부하므로 gradle·git 은 단일 명령으로, 파일 편집은 Edit/Write 로.

---

## Phase 1: Foundational — 포트 예외·에러코드·설정 (블로킹)

**Purpose**: 모든 스토리가 참조하는 타입. Red 는 이를 참조하는 테스트의 컴파일 실패.

- [ ] T001 Red: `common/src/test/kotlin/com/kbap/common/infra/llm/menu/OpenAiMenuBoardVisionExtractorTest.kt` 에 given("벤더 429 응답") 블록 추가 — `chatModelThrowing(e: Throwable)` 헬퍼로 `RateLimitException.builder().headers(Headers.builder().put("x-should-retry", "false").build()).build()` 를 던지는 ChatModel 을 만들고 `extract()` 가 `MenuBoardVisionRateLimitedException` 을 던지며 `exhausted shouldBe false`·`retryAfterSeconds shouldBe null` 을 단언. `RateLimitException.builder().error(ErrorObject 로 code="insufficient_quota")…` 는 `MenuBoardVisionQuotaExhaustedException` 이고 `code shouldBe "insufficient_quota"`. `./gradlew :common:compileTestKotlin` 이 unresolved reference 로 실패함을 확인(`ErrorObject` 빌더 시그니처는 `com.openai.models.ErrorObject.builder().code(...).message(...).type(...).build()` — 없으면 `error(JsonField)` 대체).
- [ ] T002 `common/src/main/kotlin/com/kbap/common/port/llm/MenuBoardVisionExtractor.kt` 에 `class MenuBoardVisionRateLimitedException(val retryAfterSeconds: Long?, val exhausted: Boolean, limits: String, cause: Throwable) : RuntimeException(limits, cause)` 와 `class MenuBoardVisionQuotaExhaustedException(val code: String, cause: Throwable) : RuntimeException(code, cause)` 추가(SDK 타입 참조 금지 — `ModuleBoundaryTest` 포트 순수 규칙).
- [ ] T003 [P] `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` 에 `SCAN_RATE_LIMITED("SCAN-008", 503, "일시적으로 요청이 많습니다. 잠시 후 다시 시도해 주세요")` 를 `INVALID_SCAN_TICKET` 다음에 추가.
- [ ] T004 [P] `common/src/main/kotlin/com/kbap/common/infra/llm/config/LlmModelProperties.kt` `VisionProps`: `maxRetries: Int = 0`(nullable 제거), `retryBudget: Duration = Duration.ofSeconds(10)` 추가. `LlmConfiguration.visionChatOptions` 의 `props.maxRetries?.let { builder.maxRetries(it) }` → `builder.maxRetries(props.maxRetries)`, `menuBoardVisionExtractor` 가 `retryBudget = props.retryBudget` 전달.
- [ ] T005 `./gradlew :common:compileTestKotlin` 성공, `./gradlew :common:test` 에서 T001 테스트만 실패(`RateLimitException` 이 `catch (Exception)` 없이 그대로 전파되거나 `RuntimeException`) 확인. 커밋 `feat(common): 스캔 rate-limit 포트 예외·SCAN-008·비전 재시도 예산 설정 추가`.

**Checkpoint**: 타입 존재, 어댑터 테스트 Red.

---

## Phase 2: User Story 1 — 사용자는 rate-limit 을 "잠시 후 재시도"로 안내받는다 (Priority: P1) 🎯 MVP

**Goal**: 429 즉시 갈래(quota·`x-should-retry:false`)를 어댑터가 포트 예외로 바꾸고, ScanService 가 SCAN-008/SCAN-006 으로 매핑. 횟수 미차감. 죽은 `TransientAiException` catch 를 SDK 타입으로 교정.

**Independent Test**: `./gradlew :common:test` 의 T001 케이스 그린 + `./gradlew :api:test` 의 `ScanControllerTest` SCAN-008 케이스 그린(v1·v2, 횟수 불변).

### Tests for User Story 1 (Test-First) ⚠️

- [ ] T006 [P] [US1] `api/src/test/kotlin/com/kbap/api/scan/FakeMenuBoardVisionExtractor.kt` 에 `rateLimitedOn(path: String, retryAfterSeconds: Long? = null)` 추가 — `extract()` 가 해당 path 면 `MenuBoardVisionRateLimitedException(retryAfterSeconds, exhausted = false, "remaining-requests=0", RuntimeException("rate-limit(테스트)"))` 을 던진다. `quotaExhaustedOn(path)` 도 추가(`MenuBoardVisionQuotaExhaustedException("insufficient_quota", …)`).
- [ ] T007 [US1] `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` — v1 `POST /api/scans` 섹션(기존 "비전 인식이 실패하면" 옆)에 `when`("벤더 요청 한도 초과로 스캔이 실패하면") then("503 SCAN-008 로 응답한다"); v2 섹션(기존 SCAN-006 케이스 옆)에 then("503 SCAN-008 로 응답하고 횟수가 소모되지 않는다") — `setScanCount(memberId, 2)` 후 `scanCountOf(memberId) shouldBe 2`; `quotaExhaustedOn` 케이스는 then("503 SCAN-006 으로 응답한다"). 회원 id 는 파일 내 미사용 번호(예: 647·648·509). `./gradlew :api:test` 에서 이 케이스만 실패(현재 SCAN-002) 확인.

### Implementation for User Story 1

- [ ] T008 [US1] `common/src/main/kotlin/com/kbap/common/infra/llm/menu/OpenAiMenuBoardVisionExtractor.kt` — 생성자에 `retryBudget: Duration = Duration.ofSeconds(10)`, `sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) }` 추가. `extract()` 의 `try { chatModel.call } catch (TransientAiException) … catch (ResourceAccessException)` 을 `callWithRetryBudget(prompt)` 로 교체하고 이번 단계에선 즉시 갈래만: `RateLimitException` → `code() in QUOTA_CODES` 면 `MenuBoardVisionQuotaExhaustedException`, `x-should-retry == "false"` 면 `MenuBoardVisionRateLimitedException(retryAfterOf(headers)?.seconds, exhausted = false, limitsOf(headers), e)`, 그 외 429·`InternalServerException`·`OpenAIIoException` 은 일단 `MenuBoardVisionUnavailableException(e)`(US3 에서 예산 루프로 확장). `retryAfterOf`(retry-after-ms → retry-after)·`limitsOf`(x-ratelimit-{limit,remaining,reset}-{requests,tokens} + project-tokens, `key=value` 공백 연결)·`QUOTA_CODES` companion 구현. `TransientAiException`·`ResourceAccessException` import 제거.
- [ ] T009 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt` — `catch (e: MenuBoardVisionRateLimitedException)` → `BusinessException(ErrorCode.SCAN_RATE_LIMITED, payload = e.retryAfterSeconds?.let { mapOf("retryAfterSeconds" to it) })`, `catch (e: MenuBoardVisionQuotaExhaustedException)` → `BusinessException(ErrorCode.SCAN_VISION_UNAVAILABLE)` 를 기존 `MenuBoardVisionUnavailableException` catch 앞에 추가(로그는 US2 에서).
- [ ] T010 [US1] `./gradlew :common:test` 그린(T001 케이스 포함, 기존 어댑터 테스트 — `chatModelThrowing()` 의 `RuntimeException` 경로가 여전히 전파되는지 확인), `./gradlew :api:test` 그린(T007 포함, 기존 SCAN-002/006 케이스 유지). 커밋 `feat(scan): OpenAI 429 를 SCAN-008 로 분리하고 비전 어댑터 예외 매핑을 SDK 타입으로 교정`.

**Checkpoint**: 429 즉시 갈래가 SCAN-008/006 으로 나가고 횟수 불변.

---

## Phase 3: User Story 2 — 운영자는 로그에서 rate-limit 을 구분한다 (Priority: P1)

**Goal**: `rate-limit` 마커 WARN(갈래·retryAfter·limits), quota 는 ERROR.

**Independent Test**: `./gradlew :api:test -i` 로그에 `메뉴판 비전 rate-limit — kind=IMMEDIATE retryAfterSeconds=null limits=remaining-requests=0 imagePath=…` 와 `메뉴판 비전 quota 소진 — code=insufficient_quota` 가 있고, 같은 요청에 "메뉴판 비전 인식 실패" 가 없다(quickstart §3).

### Implementation for User Story 2

- [ ] T011 [US2] `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt` — T009 의 두 catch 에 로그 추가: `log.warn("메뉴판 비전 rate-limit — kind={} retryAfterSeconds={} limits={} imagePath={}", if (e.exhausted) "EXHAUSTED" else "IMMEDIATE", e.retryAfterSeconds, e.message, imagePath, e)` / `log.error("메뉴판 비전 quota 소진 — code={} imagePath={}", e.code, imagePath, e)`.
- [ ] T012 [US2] `./gradlew :api:test -i` 실행 후 로그에서 두 마커를 grep 으로 확인(스크래치패드에 로그 저장 → `grep -c "비전 rate-limit"`, `grep -c "quota 소진"`), SCAN-008 케이스 요청에 "비전 인식 실패" 부재 확인. 커밋 `feat(scan): rate-limit·quota 로그 마커`.

---

## Phase 4: User Story 3 — 일시적 429 는 예산 안에서 재시도 (Priority: P2)

**Goal**: 일시적 429·5xx·IO 를 `Retry-After`(없으면 지수+지터) 간격으로 예산(10s) 안에서 재시도, 소진 시 429 → `RateLimited(exhausted=true)`, 5xx/IO → `Unavailable`.

**Independent Test**: `./gradlew :common:test` 의 예산 케이스 그린 — sleep 호출 시간 합이 예산 이하.

### Tests for User Story 3 (Test-First) ⚠️

- [ ] T013 [US3] `OpenAiMenuBoardVisionExtractorTest.kt` 에 given("일시적 벤더 오류 재시도") 추가 — `sleeps = mutableListOf<Duration>()` 를 기록하는 `sleep` 을 주입하고 `retryBudget = Duration.ofSeconds(3)`: (a) 첫 호출 429(`Retry-After: 1`) 후 성공 → 결과 반환·`sleeps shouldBe listOf(1s)`; (b) 항상 429(`Retry-After: 2`) → `MenuBoardVisionRateLimitedException` `exhausted shouldBe true`·`retryAfterSeconds shouldBe 2`·`sleeps.sumOf { it.seconds } <= 3`; (c) 항상 429 헤더 없음 → 예외 전 sleep 들이 0.375s~0.625s, 0.75s~1.25s 범위(지수+지터); (d) `InternalServerException.builder()…` 후 성공 → 성공; (e) 항상 `OpenAIIoException("timeout")` → `MenuBoardVisionUnavailableException`; (f) `BadRequestException` → 그대로 전파(`shouldThrow<BadRequestException>`). ChatModel 페이크는 호출 횟수별 결과 큐(`ArrayDeque<() -> ChatResponse>`)로. 실행 → (a)(b)(c)(d)(e) 실패 확인(현재 Unavailable 즉시).

### Implementation for User Story 3

- [ ] T014 [US3] `OpenAiMenuBoardVisionExtractor.kt` `callWithRetryBudget` 를 plan.md 설계로 완성 — `deadline = Instant.now() + retryBudget`, `var attempt = 0`, `waitWithinBudget(retryAfter, attempt++, deadline)`(다음 시도 예상 시각이 deadline 초과면 false), `backoffWithJitter(attempt) = 500ms shl attempt × Random(0.75..1.25)`; 429 소진 → `RateLimited(exhausted = true)`, 5xx/IO 소진 → `Unavailable`. `sleep` 이 `Duration.ZERO` 면 호출하지 않는다.
- [ ] T015 [US3] `./gradlew :common:test` 그린. 커밋 `feat(common): 비전 호출 재시도를 예산 기반으로 — Retry-After 존중·지수 백오프·10초 상한`.

---

## Phase 5: User Story 4 — Retry-After 를 응답 payload 로 (Priority: P3)

**Goal**: `payload.retryAfterSeconds` 유무 계약(contracts/scan-rate-limit-error.md).

### Tests for User Story 4 (Test-First) ⚠️

- [ ] T016 [US4] `ScanControllerTest.kt` v2 섹션에 then("Retry-After 가 있으면 payload.retryAfterSeconds 로 내려주고 없으면 payload 가 null 이다") — `vision.rateLimitedOn(path, retryAfterSeconds = 20)` → `jsonPath("$.payload.retryAfterSeconds") { value(20) }`; `rateLimitedOn(path2)` → `jsonPath("$.payload") { value(null) }`. T009 구현으로 이미 그린이면 Red 없이 회귀 고정으로 둔다(사유를 커밋 메시지에).

### Implementation for User Story 4

- [ ] T017 [US4] `api/src/main/kotlin/com/kbap/api/scan/ScanApi.kt`·`ScanV2Api.kt` — 503 `ApiResponse` description 에 `벤더 요청 한도 초과(SCAN-008 — 잠시 후 재시도, payload.retryAfterSeconds)` 추가, `errorCodes` 배열에 `ErrorCode.SCAN_RATE_LIMITED`. `api/src/test/kotlin/com/kbap/api/openapi/OpenApiSnapshotTest.kt` 스캔 설명 단언에 `scanDesc.contains("SCAN-008").shouldBeTrue()` 추가.
- [ ] T018 [US4] `./gradlew :api:test` 그린. 커밋 `docs(scan): API 문서에 SCAN-008 추가·Retry-After payload 계약 테스트`.

---

## Phase 6: Polish

- [ ] T019 [P] `../kbap-agenthub/wiki/scan-credit-limit-design.md` 의 SCAN-006 설명("어댑터가 Spring AI TransientAiException…")을 SDK 예외 기준으로 정정하고 SCAN-008·429 세 갈래·재시도 예산(`retry-budget` 10s, `max-retries` 0)·헤더 로그를 추가. `INDEX.md` 해당 줄 갱신. 허브 커밋 `docs(wiki): 스캔 rate-limit 분리(KB-394)`.
- [ ] T020 [P] `k6/scan-burst.js` 의 rate-limit 주석("앱은 OpenAI rate-limit 을 503 SCAN-002/SCAN_VISION_UNAVAILABLE 로 응답")을 SCAN-008 기준으로 갱신하고 `scanRateLimited` 카운터가 `code === "SCAN-008"` 을 세도록 분기 추가(응답 본문 파싱이 이미 있으면 그 자리).
- [ ] T021 `./gradlew build` 그린(ArchUnit 포함 — 포트가 SDK 를 참조하지 않는지), Kotlin 주석 0건(`git diff develop -- '*.kt' | grep '^+.*//'`). `ponytail-review` 로 어댑터 루프 과설계 점검.
- [ ] T022 `open-draft-pr-to-develop` — draft PR(base develop, `Refs KB-394`, 기능 흐름 섹션에 spec 의 시퀀스 다이어그램 요약 mermaid). 본문에 "Jira 전제 교정(SDK 경로·죽은 catch)" 명시.

---

## Dependencies & Execution Order

- Foundational: T001 → T002 → T003‖T004 → T005
- US1: T006‖T007 → T008 → T009 → T010 (T008 이 어댑터 즉시 갈래, US3 가 확장)
- US2: T011 → T012 (US1 뒤, 같은 파일)
- US3: T013 → T014 → T015 (US1 의 T008 뒤)
- US4: T016 → T017 → T018 (US1 뒤)
- Polish: T019‖T020 → T021 → T022

## Parallel Example

```bash
# Foundational: 에러코드와 설정은 파일이 달라 병렬
Task: "ErrorCode.kt 에 SCAN_RATE_LIMITED"
Task: "LlmModelProperties/LlmConfiguration retryBudget·maxRetries"

# US1 Red: 페이크와 컨트롤러 테스트 동시
Task: "FakeMenuBoardVisionExtractor.rateLimitedOn/quotaExhaustedOn"
Task: "ScanControllerTest SCAN-008 케이스"
```

## Implementation Strategy

- MVP = Foundational + US1: 429 가 더 이상 "인식 실패" 로 안 나가고 횟수도 안 깎임. 이 시점에 죽은 catch 교정도 끝나 5xx/IO 가 SCAN-006 으로 복구된다.
- US2 는 로그 두 줄, US3 는 루프 확장, US4 는 문서·계약 고정. 커밋 5 + 위키 1.

## Notes

- 워크트리 격리 훅: `./gradlew …` 하나, `git add …`/`git commit -F <file>` 각각 단일 명령으로.
- SDK 예외 빌더가 테스트에서 필요한 필드(`headers`·`error`)를 요구하면 `Headers.builder().build()` 빈 헤더와 `JsonField.of(...)`/`ErrorObject.builder()` 로 채운다 — 첫 컴파일에서 시그니처를 확인.
- `Random` 지터 테스트는 범위 단언으로(정확값 금지).
