# Tasks: 관리자 페이지 — 음식 데이터 적재 현황·회원 관리 화면

**Input**: Design documents from `specs/kb-245-admin-page-ui/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-pages.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 I) — 모든 스토리는 실패 테스트(Red) 선작성 → 최소 구현(Green) → 리팩터. 테스트는 전부 Kotest `BehaviorSpec`(given/when/then 한국어).

**Organization**: 유저 스토리별 페이즈. US1(로그인·레이아웃)이 베이스라 US2~4 는 US1 완료 후 착수. **PR 2개로 분할 구현**(2026-07-29 Codex 리뷰 반영): **PR-A = Phase 1~3(인증 기반+로그인+레이아웃, MVP)**, **PR-B = Phase 4~7(대시보드·폼·회원 화면)**.

## Format: `[ID] [P?] [Story] Description`

---

# PR-A: 관리자 인증 기반 + 로그인·레이아웃 (MVP)

## Phase 1: Setup

- [X] T001 `:api` 에 타임리프·BCrypt 의존 추가 — `api/build.gradle.kts` 에 `"implementation"(libs.<thymeleaf-starter>)`(카탈로그 등록 필요 시 `gradle/libs.versions.toml` 함께)와 `spring-security-crypto` 추가. `./gradlew :api:compileKotlin` 통과 확인

---

## Phase 2: Foundational — `admin_account` 영속 기반

**⚠️ CRITICAL**: 이 페이즈 완료 전 유저 스토리 착수 금지

- [X] T002 [Red] `AdminAccountJpaRepository` 통합 테스트 작성 — `common/src/test/kotlin/com/kbap/common/domain/admin/AdminAccountJpaRepositoryTest.kt` (기존 `FoodJpaRepositoryTest` 패턴 — Testcontainers·`@ServiceConnection`·엔티티 기반 스키마 생성). 시나리오: `findByLoginId` 존재/미존재, 소프트삭제 계정 미조회(`@SQLRestriction`). **실행해 실패(Red) 확인**
- [X] T003 `AdminAccount` 엔티티 + `AdminAccountJpaRepository` 구현 — `common/src/main/kotlin/com/kbap/common/domain/admin/model/AdminAccount.kt`(BaseEntity 상속, `login_id` VARCHAR(50) unique·`password` VARCHAR(60), data-model.md 참조) + `common/src/main/kotlin/com/kbap/common/domain/admin/AdminAccountJpaRepository.kt`(`findByLoginId`). T002 Green 확인
- [X] T004 `admin_account` Flyway 마이그레이션 작성 — `api/src/main/resources/db/migration/V<생성시각 timestamp>__create_admin_account_table.sql`(점 구분 timestamp 규칙, `uk_admin_account_login_id` unique key 포함, 독립 실행 가능하게). `./gradlew :api:test --tests "*KbapApiApplicationTests*"` 로 Flyway↔엔티티 정합(`ddl-auto=validate`) 통과 확인
- [X] T005 `ModuleBoundaryTest` 에 `common.domain.admin` 컨텍스트 등록(허용 맵 — 타 도메인 의존 0) — `api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt`. `./gradlew :api:test -Dkotest.tags="arch"` 통과 확인

**Checkpoint**: 계정 영속 기반 완료 — US1 착수 가능

---

## Phase 3: User Story 1 — 관리자 페이지 베이스와 내비게이션 (Priority: P1) 🎯 MVP

**Goal**: 자체 로그인(계정 테이블) + ADMIN JWT 쿠키 + 사이드바 레이아웃 + 비관리자 차단 + 관리자↔회원 토큰 교차 사용 차단

**Independent Test**: 계정 INSERT 후 로그인 → 홈(`/admin`) 진입, 미인증/오류 자격 증명 차단 확인 (spec US1 시나리오 5개)

### Tests for User Story 1 (Red 먼저 — 실패 확인 후 구현) ⚠️

- [X] T006 [P] [US1] `AdminLoginService` 단위 테스트(구현 노트: 프로젝트에 모킹 라이브러리가 없어 JpaRepository 수제 페이크 비용 > 가치 — 3분기 시나리오를 T008 통합 테스트로 흡수) — `api/src/test/kotlin/com/kbap/api/admin/AdminLoginServiceTest.kt`(페이크 `TokenIssuer`·페이크/모킹 리포지토리). 시나리오: 자격 증명 일치 → ADMIN 토큰 발급, 비밀번호 불일치/미존재 계정 → 로그인 실패(동일 사유 — 계정 존재 여부 비노출). Red 확인
- [X] T007 [P] [US1] `AdminPageAuthInterceptor` 단위 테스트 — `api/src/test/kotlin/com/kbap/api/admin/AdminPageAuthInterceptorTest.kt`(MockHttpServletRequest). 시나리오: 쿠키 없음/무효·만료 토큰/USER role → `/admin/login` 리다이렉트, ADMIN → 통과, **POST 인데 Origin 헤더가 자기 오리진과 불일치 → 거절**(CSRF 최소 방어). Red 확인
- [X] T008 [P] [US1] 로그인·페이지 접근 MockMvc 통합 테스트 — `api/src/test/kotlin/com/kbap/api/admin/AdminPageControllerTest.kt`(`@SpringBootTest`+Testcontainers, BCrypt 해시 계정 저장 후). 시나리오(contracts/admin-pages.md): `GET /admin/login` 200 뷰, `POST /admin/login` 성공 → **세션 쿠키(HttpOnly·Secure·SameSite=Strict·Path=/admin, Max-Age 미지정)** + 302 `/admin`, 실패 → 200+오류 문구, 미인증 `GET /admin` → 302 `/admin/login`, **인증 상태 `GET /admin/login` → 302 `/admin`**, `POST /admin/logout` → 쿠키 만료(동일 Path)+302, **중복 `login_id` INSERT → unique 위반(Flyway 스키마 검증)**. Red 확인
- [X] T009 [P] [US1] 관리자 토큰의 회원 API 교차 사용 거절 테스트 — `api/src/test/kotlin/com/kbap/api/core/auth/AuthMemberIdArgumentResolverTest.kt`(또는 기존 회원 API MockMvc 테스트에 추가): role=ADMIN 액세스 토큰을 Bearer 헤더로 회원 API(`@AuthMemberId`/`@AuthMemberIdOrNull` 경로)에 보내면 인증 거절. Red 확인

### Implementation for User Story 1

- [X] T010 [US1] `AdminLoginService` 구현 — `api/src/main/kotlin/com/kbap/api/admin/AdminLoginService.kt`(`findByLoginId` + `BCryptPasswordEncoder.matches` + `TokenIssuer.issueAccessToken(adminAccount.id, MemberRole.ADMIN)`, `@Transactional(readOnly = true)`). T006 Green
- [X] T011 [US1] `AdminPageAuthInterceptor` 구현(쿠키 → `TokenParser.parseAccessToken` → role==ADMIN, POST Origin 검사, 실패 시 302 `/admin/login`) — `api/src/main/kotlin/com/kbap/api/admin/AdminPageAuthInterceptor.kt`. T007 Green
- [X] T012 [US1] `@AuthMemberId` 리졸버 가드 — `api/src/main/kotlin/com/kbap/api/core/auth/AuthMemberIdArgumentResolver.kt`·`AuthMemberIdOrNullArgumentResolver.kt` 에서 role=ADMIN 토큰의 회원 신원 해석 거절(주체 혼동 차단). T009 Green
- [X] T013 [US1] 공통 레이아웃 + 로그인/홈 화면 + 디자인 토큰 CSS — `api/src/main/resources/templates/admin/layout.html`(사이드바: 음식 데이터·회원 관리, 활성 표시)·`templates/admin/login.html`·`templates/admin/home.html`(빈 홈 — US2 에서 대시보드 리다이렉트로 교체)·`api/src/main/resources/static/assets/admin.css`(**URL `/assets/admin.css` — `/admin/**` 인터셉터 범위 밖**, CSS 변수 토큰, 768px 고정형 — frontend-design 스킬 활용, spec FR-011·FR-012)
- [X] T014 [US1] `AdminPageController`(login GET/POST·logout·`GET /admin` 홈) 구현 + `WebConfig` 인터셉터 등록(`/admin/**`, `/admin/login` 제외) — `api/src/main/kotlin/com/kbap/api/admin/AdminPageController.kt`, `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt`. T008 Green

**Checkpoint — PR-A 오픈 지점**: 로그인 → 홈 진입·차단·교차 사용 거절까지 동작 + 768px 로그인/홈 확인. `./gradlew build` 통과 후 draft PR(base=develop)

---

# PR-B: 관리 화면 3종 (대시보드·작업 실행·회원)

## Phase 4: User Story 2 — 음식 데이터 적재 현황 대시보드 (Priority: P1)

**Goal**: 상태별(INCOMPLETE·PENDING_IMAGE·PENDING_REVIEW·READY) 건수 + READY 비율 가시화

**Independent Test**: 상태 분포 시드 후 `/admin/foods` 진입 → 건수·비율 일치 확인 (spec US2 시나리오 3개)

### Tests for User Story 2 (Red 먼저) ⚠️

- [ ] T015 [P] [US2] `FoodJpaRepository` 상태별 집계 통합 테스트 추가 — `common/src/test/kotlin/com/kbap/common/domain/food/FoodJpaRepositoryTest.kt`(상태 분포 저장 → group-by 결과, 0건 상태 미반환 확인). Red 확인
- [ ] T016 [P] [US2] 대시보드 MockMvc 통합 테스트 — `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPageControllerTest.kt`(ADMIN 쿠키). 시나리오: `GET /admin/foods` → 뷰 `admin/foods` + 모델 `AdminFoodDashboardView`(총계·상태별 4종 0 채움·readyRatio), 음식 0건 → total 0 오류 없음, 미인증 → 302 `/admin/login`. Red 확인

### Implementation for User Story 2

- [ ] T017 [US2] `FoodJpaRepository` 에 `countGroupByContentStatus` JPQL 집계 + projection 추가 — `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt`. T015 Green
- [ ] T018 [US2] `AdminFoodDashboardService` + `AdminFoodDashboardView` 구현(4개 상태 0 채움·readyRatio 계산, `@Transactional(readOnly = true)`) — `api/src/main/kotlin/com/kbap/api/admin/AdminFoodDashboardService.kt`
- [ ] T019 [US2] `AdminFoodPageController` `GET /admin/foods` + 대시보드 템플릿 + 홈을 `/admin/foods` 리다이렉트로 교체 — `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt`, `api/src/main/resources/templates/admin/foods.html`(상태 카드·READY 비율 바), `AdminPageController` 홈 수정(`templates/admin/home.html` 삭제). T016 Green

**Checkpoint**: 대시보드 단독 검증 가능

---

## Phase 5: User Story 3 — 화면에서 관리자 작업 실행 (Priority: P2)

**Goal**: 시드 등록·이미지 배치 제출을 화면 폼으로 실행(기존 서비스 빈 재사용, PRG)

**Independent Test**: 폼 제출 → 결과 표시, 형식 오류 → 실패 사유·데이터 무변경 (spec US3 시나리오 4개)

### Tests for User Story 3 (Red 먼저) ⚠️

- [ ] T020 [US3] 폼 액션 MockMvc 통합 테스트 추가 — `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPageControllerTest.kt` 에 시나리오 추가: `POST /admin/foods/seed`(textarea **줄 단위 파싱** — 공백 줄 무시) 성공 → 302 `/admin/foods?seeded=N`(**결과는 flash 아닌 query parameter — 무상태, prod 2대**), **빈 입력 → 오류 파라미터·DB 무변경**, `POST /admin/foods/images` 대상 있음/0건 → 각각 결과 파라미터, 처리 중 예외 → 오류 파라미터로 리다이렉트(JSON 노출 금지). Red 확인

### Implementation for User Story 3

- [ ] T021 [US3] `AdminFoodPageController` 에 seed·images POST 핸들러 구현 — textarea 입력을 줄 단위 `List<String>` 으로 변환·빈 입력 사전 검증 후 기존 `AdminController` 가 쓰는 서비스 빈 직접 호출, 결과/오류를 query parameter 로 302 리다이렉트(try/catch — 전역 JSON 핸들러로 흘리지 않음) + `templates/admin/foods.html` 에 폼·결과 배너 추가. T020 Green

**Checkpoint**: 대시보드에서 두 작업 실행 가능 — 기존 REST(`/api/v1/admin/**`) 무변경 확인

---

## Phase 6: User Story 4 — 회원 목록·상세 조회 (Priority: P2)

**Goal**: 회원 페이징 목록 + 프로필·상태 상세

**Independent Test**: 회원 시드 후 목록 페이징·상세 진입·빈 목록·미존재 id 안내 확인 (spec US4 시나리오 4개)

### Tests for User Story 4 (Red 먼저) ⚠️

- [ ] T022 [US4] 회원 화면 MockMvc 통합 테스트 — `api/src/test/kotlin/com/kbap/api/admin/AdminMemberPageControllerTest.kt`(ADMIN 쿠키). 시나리오: `GET /admin/members` 페이징(**page 1-based**, 21건 시드 → 2페이지·id desc)·빈 목록·**범위 초과/음수/비숫자 page → 1페이지 보정(오류 미노출)**, `GET /admin/members/{id}` 상세 모델(프로필·상태·**프로필 이미지는 공개 URL 로 해석**), 미존재 id → 안내 화면. Red 확인

### Implementation for User Story 4

- [ ] T023 [US4] `AdminMemberQueryService` + 뷰 모델(`AdminMemberPageView`·`AdminMemberSummaryView`·`AdminMemberDetailView`) 구현 — `api/src/main/kotlin/com/kbap/api/admin/AdminMemberQueryService.kt`(`findAll(PageRequest, id desc)`·`findById`, page 보정, 프로필 이미지 `ImageUrls.resolve` 경유 — 기존 `MemberService` 패턴, `@Transactional(readOnly = true)`)
- [ ] T024 [US4] `AdminMemberPageController` + 템플릿 — `api/src/main/kotlin/com/kbap/api/admin/AdminMemberPageController.kt`, `api/src/main/resources/templates/admin/members.html`(테이블+페이지네이션)·`templates/admin/member-detail.html`. T022 Green

**Checkpoint**: 전 스토리 독립 동작

---

## Phase 7: Polish & Cross-Cutting

- [ ] T025 [P] 768px(아이패드 미니) 레이아웃 검증·디자인 다듬기 — 전 화면 가로 스크롤 없음(quickstart 시나리오 5, spec SC-005), `admin.css` 토큰 일관성
- [ ] T026 전체 검증 — `./gradlew build`(ArchUnit 포함 전 모듈) 통과 + quickstart.md 검증 시나리오 1~4 수동 확인(로컬 bootRun + 계정 INSERT) → PR-B 오픈

---

## Dependencies & Execution Order

- **Phase 1 → 2 → 3** 직렬 = **PR-A**. Phase 3 내부: 테스트(T006~T009 병렬 작성·Red) → 구현 T010~T012 → 템플릿 T013 → 컨트롤러·배선 T014(통합 Green 은 템플릿 필요).
- **PR-A 머지 후 Phase 4~7 = PR-B**. US2 → US3 순서 고정(`foods.html`·`AdminFoodPageController` 공유). US4 는 US2·US3 과 파일이 겹치지 않아 병렬 가능.
- 각 스토리 내부: Red → Green → Refactor. 테스트 Red 확인 없이 구현 착수 금지(헌법 I).
- 태스크/논리 단위마다 커밋.

### Parallel Opportunities

- T006·T007·T008·T009 (US1 테스트 4종 — 서로 다른 파일) 동시 작성 가능.
- T015·T016 (US2 테스트 2종) 동시 작성 가능.
- US4 는 US2/US3 과 완전 병렬 가능(PR-B 내).

## Implementation Strategy

**PR-A(Phase 1~3) = MVP**: 인증 경계(계정 테이블·로그인·인터셉터·교차 사용 가드)가 집중된 부분 — 단독 리뷰 후 develop 머지. **PR-B(Phase 4~7)**: 인증 위에 얹는 읽기/쓰기 화면 3종. 사용자 지시(2026-07-29): 최소 구현 집중 — rate limit·계정 관리 화면·감사 로그·CSRF 토큰 체계(Origin 검사로 갈음) 등 범위 밖 확장 금지.
