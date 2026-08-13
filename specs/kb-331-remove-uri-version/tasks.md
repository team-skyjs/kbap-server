# Tasks: 전 API URI 버전 제거 + 버전 헤더 필수화

**Input**: Design documents from `/specs/kb-331-remove-uri-version/`

**Prerequisites**: plan.md, spec.md, research.md, contracts/path-migration.md, quickstart.md

**Tests**: 사용자 지시(2026-08-13) — **신규 테스트 클래스는 작성하지 않는다.** 기존 테스트의 경로 치환 + 전체 그린이 회귀 그물이고, 헤더 필수화 신규 동작은 quickstart 의 수동 curl 로 검증한다.

**Organization**: US1(경로 이동) → US2(헤더 필수화) 순차. 경로 이동이 끝나야 필수화 검증이 새 경로 기준으로 의미를 가진다.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [ ] T001 테스트 공통 MockMvc 기본 헤더 주입 — `MockMvcBuilderCustomizer` 빈(`defaultRequest` 에 `X-API-Version: 1.0`)을 컴포넌트 스캔되는 테스트 설정으로 추가 in `api/src/test/kotlin/com/kbap/api/core/config/MockMvcDefaultVersionConfig.kt` (기존 `FakeVisionConfig` 와 같은 방식 — test classpath 의 `@Configuration` 은 전 통합 테스트에 스캔됨. 기존에 헤더를 명시하는 테스트(scan v2)와의 병합에서 명시 값이 이기는지 확인)

---

## Phase 2: Foundational (Blocking Prerequisites)

- [ ] T002 `GlobalExceptionHandler` 에 `MissingApiVersionException`·`InvalidApiVersionException` 핸들러 추가 — 400 + `BaseResponse.fail("COMMON-002", ...)` 봉투 (현재는 `Exception` 폴백으로 500 COMMON-003 유출) in `api/src/main/kotlin/com/kbap/api/core/GlobalExceptionHandler.kt`

**Checkpoint**: 컴파일 + 기존 테스트 영향 없음(`./gradlew :api:compileKotlin`)

---

## Phase 3: User Story 1 - 단일 경로 체계 (Priority: P1) 🎯 MVP

**Goal**: 전 비즈니스 API 를 `/api/<리소스>` 로 통일, 레거시 `/api/v1` 소멸

**Independent Test**: 기존 테스트 전부가 새 경로로 그린 + `grep 'api/v1'` 0건

- [ ] T003 [US1] 컨트롤러 12개 매핑 `ApiPaths.V1` → `ApiPaths.API` 치환 in `api/src/main/kotlin/com/kbap/api/{home/HomeController,bookmark/BookmarkController,auth/AuthController,member/MemberController,scan/ScanController,image/ImageUploadUrlController,image/ImageController,report/ReportController,food/FoodController,community/CommunityController,block/MemberBlockController}.kt` (v1 스캔은 `/api/scans` 로 이동해 v2 와 동일 경로 — 버전 속성 분기 확인)
- [ ] T004 [US1] `WebConfig` JWT `addUrlPatterns` 13항목·게스트 예외 정규식 2건 `ApiPaths.V1` → `ApiPaths.API` 치환 + `ApiPaths.V1` 상수 삭제 in `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt`, `api/src/main/kotlin/com/kbap/api/core/ApiPaths.kt`
- [ ] T005 [P] [US1] swagger `*Api` 인터페이스·요청 DTO 문구의 `/api/v1` 서술을 새 경로로 갱신 in `api/src/main/kotlin/com/kbap/api/**/*Api.kt` (`grep -rln 'api/v1' api/src/main` 으로 전수 확인)
- [ ] T006 [US1] 기존 테스트 33파일의 `/api/v1` 경로를 `/api` 로 치환 후 `./gradlew :api:test` 그린 확인 in `api/src/test/kotlin/**` (치환 후 `grep -rn 'api/v1' api/src` 0건)

**Checkpoint**: 전체 기존 테스트 그린 — 경로 이동 완결 (MVP)

---

## Phase 4: User Story 2 - 버전 명시 강제 (Priority: P2)

**Goal**: `/api/**` 에서 `X-API-Version` 필수(기본값 폐지), `/api/app-version` 만 예외

**Independent Test**: quickstart 수동 curl 4종 — 무헤더 비즈니스 API 400 COMMON-002 · app-version 무헤더 200 · 헤더 요청 정상 · 구 경로 404

- [ ] T007 [US2] `WebConfig.configureApiVersioning` 개정 — `setDefaultVersion("1.0")` 제거, `setVersionRequired(true)` 명시, 헤더 리졸버 뒤 폴백 리졸버 추가(경로가 `/api/` 로 시작하지 않거나 `/api/app-version` 이면 "1.0" 반환) in `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt`
- [ ] T008 [US2] `./gradlew :api:test` 그린 확인(T001 기본 헤더 주입으로 기존 테스트가 헤더 요청이 됨) + local bootRun 으로 quickstart 수동 curl 4종 검증, 결과를 PR 본문에 기록 (**포트 8082 사용** — 8080 은 kb-274, 8081 은 kb-328 세션 점유: `SERVER_PORT=8082 SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun`, 검증 후 즉시 종료)

**Checkpoint**: 필수화 동작 확인 — 전 스토리 완결

---

## Phase 5: Polish & Cross-Cutting Concerns

- [ ] T009 [P] 문서 갱신 — CLAUDE.md "API 엔드포인트 경로 규약" 절(V1 레거시 서술 제거·헤더 필수·app-version 예외)과 `docs/architecture/meogo-conventions.md` 대응 절 개정 in `CLAUDE.md`, `docs/architecture/meogo-conventions.md`
- [ ] T010 전체 회귀 `./gradlew build` + `grep -rn 'ApiPaths.V1\|api/v1' api common infra batch --include='*.kt'` 0건 확인, Kotlin 주석 0건 점검

---

## Dependencies & Execution Order

- T001·T002 선행(독립·병렬 가능) → US1(T003→T004→T005∥T006) → US2(T007→T008) → Polish
- T003 과 T004 는 같은 커밋 단위가 자연스럽다(ApiPaths.V1 삭제는 참조 소멸 후에만 컴파일 가능)
- T006 은 T003·T004 완료 후 실행(경로가 바뀌어야 치환 의미)
- T007 은 T001 없이는 기존 테스트를 전멸시킨다 — 반드시 T001 이후

## Implementation Strategy

**MVP = Phase 1~3**: 경로 이동만으로도 단일 체계가 성립하고 배포 가능(헤더는 아직 기본값 1.0 으로 관대). US2 는 langchain 헤더 선행 배포(plan "배포 순서") 확인 후 켜는 별도 커밋으로 분리하면 롤백도 깔끔하다. 각 task/논리 단위 커밋.
