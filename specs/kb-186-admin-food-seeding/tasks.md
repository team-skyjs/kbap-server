# Tasks: 신규 음식 적재 관리자 API (Admin Food Seeding)

**Input**: Design documents from `specs/kb-186-admin-food-seeding/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-food-seed.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 I) — 각 스토리는 실패 테스트 작성·Red 확인 후 구현(Green)한다. 테스트는 Kotest BehaviorSpec(given/when/then 한국어).

**Organization**: 스토리별 독립 구현·검증. US1 = MVP(적재), US2 = 인가, US3 = 멱등 검증.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 베이스라인 확인 — `./gradlew build` 그린인지 확인(develop 분기점 f4ba2f0). 실패 시 원인 파악 후 진행

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1(응답 카운트 아님, 경로 상수)·US2(역할·에러코드)가 공유하는 상수/enum. 전부 한 줄짜리 값 추가 — 소비 스토리의 테스트가 커버하므로 이 페이즈 자체 테스트는 없다.

- [X] T002 [P] `MemberRole` 에 `ADMIN` 추가 — domain/member/src/main/kotlin/com/kbap/domain/member/model/MemberRole.kt. 기존 파서(`JwtTokenParser` — `entries` 매칭)·`AuthApplicationService`(USER 명시) 파급 없음을 기존 테스트 그린으로 확인
- [X] T003 [P] `ErrorCode` 에 `ADMIN_FORBIDDEN("AUTH-008", 403, "관리자만 사용할 수 있는 API 입니다")` 추가 — core/src/main/kotlin/com/kbap/core/error/ErrorCode.kt
- [X] T004 [P] `ApiPaths` 에 `const val ADMIN = "$V1/admin"` 추가 — app/api/src/main/kotlin/com/kbap/app/api/common/ApiPaths.kt

**Checkpoint**: 컴파일 그린 — 스토리 구현 시작 가능

---

## Phase 3: User Story 1 - 관리자가 신규 메뉴를 INCOMPLETE 로 일괄 적재 (Priority: P1) 🎯 MVP

**Goal**: 이름 목록 제출 → 기존 korean_name 대조 → 신규만 INCOMPLETE 적재, created/skipped 카운트 응답. (인가 가드는 US2 — 이 단계의 엔드포인트는 아직 보호되지 않는다)

**Independent Test**: 신규+기존 섞인 목록 제출 시 신규만 생성되고 카운트가 맞는지, DB 에 INCOMPLETE 로 들어갔는지로 단독 검증.

### Tests for User Story 1 (Red 먼저 — 작성 직후 실패 확인) ⚠️

- [X] T005 [P] [US1] `FoodServiceTest` 에 `seedIncomplete` 시나리오 추가(Red 확인) — domain/food/src/test/kotlin/com/kbap/domain/food/FoodServiceTest.kt: ① 전부 신규 → created=n·skipped=0 ② 일부 기존 → 신규만 생성, requested=created+skipped ③ 전부 기존 → created=0 성공 ④ 빈 Set → (0,0,0) 무쿼리
- [X] T006 [P] [US1] `AdminFoodControllerTest` 신규(Red 확인) — app/api/src/test/kotlin/com/kbap/app/api/admin/AdminFoodControllerTest.kt (Testcontainers MySQL): ① ADMIN 토큰 + 신규/기존 혼합 → 200, payload 카운트, DB 에 신규만 content_status=INCOMPLETE ② 빈 배열·전부 blank → 200 (0,0,0) ③ 항목 255자 초과 → 400 COMMON-002 ④ koreanNames null → 400. ADMIN 토큰은 `JwtTokenIssuer.issueAccessToken(0, MemberRole.ADMIN)` 로 테스트에서 발급(quickstart.md 참조)

### Implementation for User Story 1

- [X] T007 [P] [US1] `SeedIncompleteResult(requested, created, skipped)` DTO 생성 — domain/food/src/main/kotlin/com/kbap/domain/food/dto/SeedIncompleteResult.kt
- [X] T008 [US1] `FoodService.seedIncomplete(koreanNames: Set<String>): SeedIncompleteResult` 구현(T005 Green) — domain/food/src/main/kotlin/com/kbap/domain/food/FoodService.kt: 기존 이름 `findByKoreanNameIn` diff → 신규만 `createIncomplete` 재사용 → 카운트 반환. `@Transactional`. 경합 시 카운트 낙관적임을 `ponytail:` 주석으로 명시(research R3)
- [X] T009 [P] [US1] 요청/응답 DTO 생성 — app/api/src/main/kotlin/com/kbap/app/api/admin/AdminFoodSeedRequest.kt(`koreanNames: List<String>?`, `@field:NotNull`·항목 `@field:Size(max=255)`, `toKoreanNames()` = trim→blank 제거→dedup), AdminFoodSeedResponse.kt(`requested/created/skipped`)
- [X] T010 [US1] `AdminFoodApi` Swagger 인터페이스 생성 — app/api/src/main/kotlin/com/kbap/app/api/admin/AdminFoodApi.kt: `@Tag`(관리자 음식 적재)·`@SecurityRequirement(bearerAuth)`·contracts/admin-food-seed.md 의 200/400/401/403 응답 문서화(기존 `ScanApi` 스타일)
- [X] T011 [US1] `AdminFoodController` 구현(T006 200 케이스 Green) — app/api/src/main/kotlin/com/kbap/app/api/admin/AdminFoodController.kt: `@PostMapping` `ApiPaths.ADMIN + "/foods"` → `request.toKoreanNames()` → `foodService.seedIncomplete` → `BaseResponse.ok(AdminFoodSeedResponse)`

**Checkpoint**: ADMIN 토큰으로 적재·카운트·INCOMPLETE 저장이 동작(단, 아직 누구나 호출 가능 — US2 가 잠금)

---

## Phase 4: User Story 2 - 관리자만 적재 가능 (ADMIN 인가) (Priority: P2)

**Goal**: `/api/v1/admin/**` 를 서명 검증(401) + role==ADMIN 검사(403 AUTH-008)로 잠근다.

**Independent Test**: 무토큰/위조 401, USER 토큰 403 + 데이터 미생성, ADMIN 토큰 200 유지.

### Tests for User Story 2 (Red 먼저 — 작성 직후 실패 확인) ⚠️

- [X] T012 [US2] `AdminFoodControllerTest` 에 인가 시나리오 추가(Red 확인) — app/api/src/test/kotlin/com/kbap/app/api/admin/AdminFoodControllerTest.kt: ① 무토큰 → 401 ② 위조 서명 토큰 → 401 ③ USER 토큰 → 403 AUTH-008 + food 행 미생성 ④ ADMIN 토큰 → 200 유지(회귀)

### Implementation for User Story 2

- [X] T013 [P] [US2] `AdminAuthorizationInterceptor` 생성 — app/api/src/main/kotlin/com/kbap/app/api/common/auth/AdminAuthorizationInterceptor.kt: `preHandle` 에서 `JwtAuthenticationFilter.ROLE_ATTRIBUTE != MemberRole.ADMIN.name` 이면 `BusinessException(ErrorCode.ADMIN_FORBIDDEN)`(기존 `@RestControllerAdvice` 가 403 직렬화)
- [X] T014 [US2] `WebMvcAuthConfig` 에 admin 경로 등록(T012 Green) — app/api/src/main/kotlin/com/kbap/app/api/common/auth/WebMvcAuthConfig.kt: `jwtAuthenticationFilterRegistration` URL 패턴에 `"${ApiPaths.ADMIN}/*"` 추가 + `addInterceptors` 로 `AdminAuthorizationInterceptor` 를 `"${ApiPaths.ADMIN}/**"` 에 등록

**Checkpoint**: US1+US2 — 보호된 적재 API 완성

---

## Phase 5: User Story 3 - 재실행·경합에도 중복 없는 멱등 적재 (Priority: P3)

**Goal**: 재실행·동시 요청에서 중복 행 0·무실패를 실행 가능한 회귀 테스트로 고정한다.

**Independent Test**: 같은 목록 2회(순차·동시) 제출 후 각 이름 1행·두 번째 created=0·무예외.

### Tests for User Story 3

> 멱등성은 기존 upsert(insert-or-ignore, kb-90)가 이미 보장하므로 이 테스트들은 **작성 즉시 그린일 수 있다**. 그 경우 Red 억지 생성 없이 "기존 보장의 회귀 고정"으로 인정한다(테스트가 없던 계약을 실행 가능하게 고정하는 것이 목적). 실패하면 그대로 결함 발견.

- [X] T015 [P] [US3] `FoodServiceTest` 에 멱등·경합 시나리오 추가 — domain/food/src/test/kotlin/com/kbap/domain/food/FoodServiceTest.kt: ① 같은 Set 2회 → 두 번째 (n,0,n)·행 수 불변 ② 2스레드 동시 동일 Set → 각 이름 정확히 1행·양쪽 무예외(kb-90 경합 테스트 스타일 재사용)
- [X] T016 [P] [US3] `AdminFoodControllerTest` 에 재실행 시나리오 추가 — app/api/src/test/kotlin/com/kbap/app/api/admin/AdminFoodControllerTest.kt: 같은 목록 2회 호출 → 둘 다 200, 두 번째 created=0·skipped=requested

**Checkpoint**: 3개 스토리 전부 독립 검증 완료

---

## Phase 6: Polish & Cross-Cutting

- [X] T017 [P] FR-009 센티널 assert 추가 — app/api/src/test/kotlin/com/kbap/app/api/admin/AdminFoodControllerTest.kt: 적재 행의 `spiciness = -1`·`avoidance_substances IS NULL` 검증. **kb-182-batch-pipeline-skeleton 머지 전이면 `@Ignore`(사유: kb-182 센티널 의존) 로 잠그고, develop rebase 후 활성화**(plan.md 의존성 절)
- [X] T018 전체 검증 — `./gradlew build`(ArchUnit `ModuleBoundaryTest` 포함) BUILD SUCCESSFUL + quickstart.md 순서로 로컬 curl 스모크(ADMIN 200 → 재실행 created=0 → USER 403)

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1(T001) → Phase 2(T002~T004, 전부 [P]) → 스토리 시작
- **US1(Phase 3)**: Foundational 이후. 다른 스토리 의존 없음 — MVP
- **US2(Phase 4)**: Foundational 이후. 테스트 대상 엔드포인트가 필요하므로 실질적으로 US1 뒤(T012 가 T011 산출 엔드포인트를 침)
- **US3(Phase 5)**: US1 의 `seedIncomplete` 필요. US2 와는 독립(병행 가능)
- Polish(Phase 6): T017 은 US1 이후 아무 때나(kb-182 머지가 활성화 조건), T018 은 마지막

### Within Each Story

- Red(테스트, 실패 확인) → Green(구현) → Refactor. T005/T006 병행 작성 가능(다른 파일)
- T007(DTO) → T008(서비스) · T009(DTO) → T010(Api) → T011(컨트롤러)

### Parallel Opportunities

- T002·T003·T004 (다른 모듈 3파일)
- T005·T006 (domain 테스트 vs api 테스트)
- T007·T009 (domain DTO vs api DTO)
- T013 은 T012 와 병행 가능(다른 파일), T014 만 T013 뒤
- T015·T016 (다른 파일) · T017 은 US2/US3 와 병행 가능

---

## Implementation Strategy

**MVP = Phase 1~3 (US1)**: 적재 코어가 돌면 가치 전달 시작 — 단 노출 전 US2(인가) 필수. 실질 배포 최소선은 **US1+US2**.

**Incremental**: US1(적재) → US2(잠금) → US3(멱등 회귀 고정) → Polish(센티널·전체 빌드). 각 체크포인트에서 `./gradlew :domain:food:test :app:api:test` 로 검증. 작업/논리 단위마다 한국어 Conventional Commits(`feat(food): …`·`feat(api): …`, 제목 끝 이슈번호 금지).

**주의(교차 세션)**: `Food.kt`·`FoodJpaRepositoryCustomImpl.kt`·Flyway 마이그레이션은 **kb-182 소유 — 이 브랜치에서 수정 금지**. T017 만 그 결과를 관찰한다.
