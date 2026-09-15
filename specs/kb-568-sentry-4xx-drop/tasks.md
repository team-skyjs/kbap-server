# Tasks: Sentry 4xx·클라이언트 끊김 이벤트 미전송

**Input**: Design documents from `/specs/kb-568-sentry-4xx-drop/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R9), data-model.md, contracts/sentry-drop-policy.md, quickstart.md

**Tests**: 없음 — 원칙 I 예외(plan Complexity Tracking, research R8). 사용자 결정 2026-09-15 "테스트 코드는 필요없음"(2026-09-09 KB-508 의 Sentry 관측 무테스트 재확인). 검증은 로컬 가짜 DSN 의 SDK drop 로그 + dev 콘솔 실이벤트(quickstart). 닫힌 PR #257 의 프로세서 diff 를 재사용한다(`gh pr diff 257` — 테스트 파일은 제외).

**Organization**: 스토리 4개. 런타임 변경은 프로세서 1파일이라 US1~US3 은 같은 파일에 분기를 누적(직렬), US4 문서만 병렬.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크 의존 없음)
- **[Story]**: 스토리 라벨(US1~US4)
- 설명에 정확한 파일 경로 포함

## Path Conventions

- 프로세서: `api/src/main/kotlin/com/kbap/api/core/observability/SentryRequestContextProcessor.kt`
- 관측 문서: `docs/observability/sentry.md`
- `application.yml`(api·batch)·`GlobalExceptionHandler`·테스트·마이그레이션 무변경

---

## Phase 1: Setup

**Purpose**: 없음 — 신규 의존 없음(`ClientAbortException` 은 tomcat-embed-core, `AsyncRequestNotUsableException` 은 spring-web — 둘 다 api 클래스패스에 이미 있음). 비어 있다.

---

## Phase 2: Foundational

**Purpose**: 없음 — 스토리가 다른 기반에 의존하지 않는다. 비어 있다.

**Checkpoint**: 바로 US1 로 진행.

---

## Phase 3: User Story 1 - 클라이언트 잘못으로 난 4xx 는 Sentry 에 남지 않는다 (Priority: P1) 🎯 MVP

**Goal**: `httpStatusOf` 가 4xx 이고 낙관적 락 원인이 없으면 `process` 가 `null` 을 돌려준다. HTTP 응답·로그 무변경.

**Independent Test**: 로컬 가짜 DSN 으로 4xx 요청(앱 에러 코드·버전 헤더 누락·미지원·타입 불일치·405) 시 `Event was dropped by a processor` 로그, 응답 코드 종전(quickstart §2). dev 배포 후 같은 요청에 새 이벤트 0건(quickstart §3 행 1·2).

### Implementation for User Story 1

- [X] T001 [US1] `api/src/main/kotlin/com/kbap/api/core/observability/SentryRequestContextProcessor.kt` 수정(research R2·R3, data-model 규칙 1·3·4) — (1) `process` 반환 타입을 `SentryEvent?` 로, (2) 기존 `when (val throwable = event.throwable)` 블록을 제거하고 그 자리에 `val throwable = event.throwable ?: return event` → `val status = httpStatusOf(throwable)` → `if (status in 400..499 && !hasOptimisticConflictCause(throwable)) return null` → `event.setTag(HTTP_STATUS_TAG, status.toString())` → `if (throwable is BusinessException) { event.setTag("error.code", throwable.errorCode.code); event.fingerprints = listOf("business", throwable.errorCode.code) }` → `return event`, (3) `private fun httpStatusOf(throwable: Throwable): Int = when (throwable) { is BusinessException -> throwable.errorCode.status; is ErrorResponse -> throwable.statusCode.value(); is IllegalArgumentException, is HttpMessageNotReadableException, is MethodArgumentTypeMismatchException -> 400; else -> if (hasOptimisticConflictCause(throwable)) 409 else 500 }` 추가, (4) `hasOptimisticConflictCause` 본문을 `causeChain(throwable).any { it is OptimisticLockingFailureException || it is OptimisticLockException }` 로 바꾸고 `private fun causeChain(throwable: Throwable): Sequence<Throwable> = generateSequence(throwable) { it.cause }` 추가. MDC 태그·`Authorization` 헤더 제거·쿼리 마스킹 코드는 그대로 앞에 둔다. Kotlin 주석 금지. `./gradlew :api:compileKotlin` 통과

**Checkpoint**: 컴파일 통과. 4xx 판정이 `GlobalExceptionHandler` 매핑과 동일(data-model 표와 대조).

---

## Phase 4: User Story 2 - 5xx 와 예상 밖 예외는 종전대로 Sentry 에 남는다 (Priority: P1)

**Goal**: 5xx `BusinessException`·낙관적 락 409·아웃바운드 I/O 실패·예외 없는 로그 이벤트가 종전 태그·핑거프린트로 통과한다. 별도 코드 없음 — T001 의 통과 경로가 그 자체다.

**Independent Test**: T001 diff 를 contracts §1 의 통과 행(3·6·7·10·11·12)과 대조. 로컬 가짜 DSN 에서 5xx 경로가 `Capturing event` 후 전송 시도(quickstart §2). dev 에서 500·낙관적 락 이벤트 태그 6종 동일(quickstart §3 행 4).

### Implementation for User Story 2

- [X] T002 [US2] T001 diff 리뷰 — `SentryRequestContextProcessor.kt` 에서 (a) `throwable == null` 이 `return event` 로 즉시 통과, (b) `status >= 500` 은 drop 조건에 걸리지 않음, (c) 낙관적 락 cause 는 `status` 가 409 여도 `!hasOptimisticConflictCause` 가 거짓이라 통과, (d) `IOException`·`SocketException` 은 `else` 분기 500 으로 통과(메시지 검사 없음), (e) 통과 이벤트의 `http.status`·`error.code`·핑거프린트 코드가 종전과 문자 단위로 동일함을 contracts §1 표 옆에 체크. 어긋나면 T001 수정 (T001 의존)

**Checkpoint**: 통과 경로 6종이 계약과 일치.

---

## Phase 5: User Story 3 - 클라이언트가 연결을 끊어 난 예외는 Sentry 에 남지 않는다 (Priority: P2)

**Goal**: 원인 체인에 `ClientAbortException` 또는 `AsyncRequestNotUsableException` 이 있으면 상태와 무관하게 drop(research R5, data-model 규칙 2).

**Independent Test**: 로컬에서 `curl --max-time 0.05 /v3/api-docs` 로 끊김 유발 시 drop 로그(quickstart §2). dev 에서 끊김 5회에 이벤트 0건(quickstart §3 행 3).

### Implementation for User Story 3

- [X] T003 [US3] `SentryRequestContextProcessor.kt` — `val throwable = event.throwable ?: return event` 바로 다음 줄에 `if (isClientAbort(throwable)) return null` 추가, `private fun isClientAbort(throwable: Throwable): Boolean = causeChain(throwable).any { it is ClientAbortException || it is AsyncRequestNotUsableException }` 추가, import `org.apache.catalina.connector.ClientAbortException`·`org.springframework.web.context.request.async.AsyncRequestNotUsableException`. 메시지 문자열 매칭 금지(FR-004). `./gradlew :api:compileKotlin` 통과 (T002 의존)

**Checkpoint**: 최종 프로세서가 PR #257 의 `SentryRequestContextProcessor.kt` 와 동등(`gh pr diff 257` 과 대조).

---

## Phase 6: User Story 4 - 수집 계약이 문서에 반영되고 batch 는 기준이 정해진다 (Priority: P3)

**Goal**: `docs/observability/sentry.md` 를 contracts §2 대로 갱신, batch 결정 한 줄.

**Independent Test**: 문서의 제목·첫 문단·태그 표·"수집되지 않는 것"·"후속·조정"·batch 문장이 contracts §2 의 6개 항목과 일치.

### Implementation for User Story 4

- [X] T004 [P] [US4] `docs/observability/sentry.md` 갱신(contracts §2) — (1) 제목 `# Sentry 에러 수집 (KB-508 · KB-568)`, (2) 첫 문단 "핸들러 예외(4xx·5xx 전부)와 ERROR 로그·배치 잡 실패" → "**5xx·낙관적 락 409·예상 밖 예외**와 ERROR 로그·배치 잡 실패(4xx·클라이언트 끊김은 미수집 — 아래)", (3) 태그 표 `http.status` 행 끝에 "— 4xx 판정이면 이벤트 자체를 버린다(KB-568)" 추가, (4) "**수집되지 않는 것**" 문단을 "필터 단계 401·400(종전) / **모든 4xx** — 앱 에러 코드 4xx(409 정상 충돌 포함)·검증 실패·파라미터 누락·본문 파싱·타입 불일치·`X-API-Version` 누락/미지원·405·415: `SentryRequestContextProcessor` 가 `GlobalExceptionHandler` 와 같은 상태 매핑으로 4xx 를 판정해 전송 직전 버린다(KB-568). 서버 로그(WARN)는 그대로 / **인바운드 클라이언트 끊김** — 원인 체인에 `ClientAbortException`·`AsyncRequestNotUsableException`: 같은 프로세서가 버린다. DB·S3·LLM 등 아웃바운드 I/O 실패("Broken pipe"·"Connection reset" 메시지)는 메시지로 판정하지 않으므로 500 으로 계속 수집 / 매핑되지 않은 경로의 404(`NoResourceFoundException`): `sentry.ignored-exceptions-for-type` 으로 SDK 가 프로세서 이전에 버린다(KB-510)" 로 교체하고 "405·415·앱 에러 코드가 붙은 4xx 는 계속 수집한다" 문장 삭제, (5) "후속·조정 → 알림" 의 "4xx 를 빼려면 조건 `http.status` starts with `5`" 삭제, "노이즈" 항목의 "특정 에러 코드 단위 제외" 를 "4xx 는 이미 제외. 5xx 중 특정 에러 코드를 빼려면 프로세서에서 그 코드에 `null`" 로 수정, (6) "수집되지 않는 것" 끝에 "batch 는 HTTP 경계가 없어 4xx·끊김 개념이 없다 — 변경 없음(ERROR 로그·잡 실패 기준 유지, KB-568 결정)" 한 줄. 검증 절 링크를 `specs/kb-568-sentry-4xx-drop/quickstart.md` 로 갱신

**Checkpoint**: 문서가 코드 판정과 일치.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 기존 테스트 회귀·로컬 실증·커밋·PR

- [X] T005 `./gradlew :api:test` 전체 통과 확인(arch 태그 포함 — tomcat 클래스 참조는 `api.core` 소속이라 경계 규칙 대상 밖, PR #257 선례). 컨텍스트 수·결과 무변화(DSN 부재로 Sentry 미기동) (T003 의존)
- [X] T006 로컬 실증(quickstart §2) — `docker compose up -d mysql redis` → 메인 `.env` source → `API_SENTRY_DSN='https://k@localhost.invalid/0' SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun --no-daemon --args='--sentry.debug=true'` → quickstart 의 curl 6종(앱 에러 코드 4xx·버전 헤더 누락·미지원 버전·타입 불일치·405·`--max-time 0.05` 끊김) 각각에서 `Event was dropped by a processor`(404 는 `is ignored`) 로그와 종전 응답 코드 확인, `Sending the event` 없음. 5xx 경로가 있으면 `Capturing event` 후 전송 시도 확인. 확인한 로그를 `specs/kb-568-sentry-4xx-drop/research.md` 끝에 "실증(2026-09-15)" 절로 기록 (T005 의존)
- [ ] T007 커밋 1개 — `fix(observability): Sentry 4xx·클라이언트 끊김 이벤트 미전송 (KB-568)` 형식, 본문에 R1(#257 결말·정책 전환 근거)·R5(메시지 매칭 금지)·R6(409 두 종류)·R8(무테스트 결정)·R9(batch 무변경) 요지와 로컬 실증 로그. 파일: `api/src/main/kotlin/com/kbap/api/core/observability/SentryRequestContextProcessor.kt`, `docs/observability/sentry.md`, `specs/kb-568-sentry-4xx-drop/*`. 끝에 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` (T004·T006 의존)
- [ ] T008 `open-draft-pr-to-develop` 스킬로 base=develop draft PR — 제목 = 커밋 제목, 본문 "무엇을/왜"(Jira 배경 3줄: 한도 5,000건/월·노이즈 4건·#257 정책 충돌 해소)·"변경 사항"(contracts §1 표 요약·문서·테스트 없음 사유)·"검증"(T005 수치·T006 로그·dev 검증은 quickstart §3 표를 배포 후 채움), `Refs KB-568`, 닫힌 #257 링크. dev 배포 후 quickstart §3 결과를 PR 본문에 추가 (T007 의존)

---

## Dependencies & Execution Order

```text
T001 (US1 4xx drop) ─ T002 (US2 통과 경로 리뷰) ─ T003 (US3 끊김 drop)
                                                     └─ T005 (전체 테스트) ─ T006 (로컬 실증) ─ T007 (커밋) ─ T008 (PR)
T004 (US4 문서) ─ 언제든 [P], T007 이전
```

- **US1 → US2 → US3 직렬**: 같은 프로세서 파일에 분기를 누적. US2 는 코드 없이 T001 diff 를 계약과 대조하는 리뷰.
- **US4 만 병렬**: 문서 파일이 독립.

## Parallel Example

```text
T001~T003 진행 중 | T004 문서 갱신 (다른 파일)
```

## Implementation Strategy

- **MVP = T001**: 4xx drop 만으로 Jira 실측 노이즈 4건 중 3건(400·400·401)이 사라진다. 이 상태로도 배포 가능.
- **증분**: T002 통과 경로 확인 → T003 끊김 drop(나머지 1건, Broken pipe) → T004 문서 → T005~T008 회귀·실증·커밋·PR.
- 총 태스크 8개 — US1 1·US2 1·US3 1·US4 1·마무리 4. 테스트 코드 0.
