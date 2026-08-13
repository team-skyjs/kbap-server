# Tasks: 앱 버전 정보 조회 (최소 지원·최신 버전과 스토어 링크)

**Input**: Design documents from `/specs/kb-329-app-version-check/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/app-version-api.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트 작성·Red 확인 후 구현한다. 테스트는 Kotest BehaviorSpec(한국어 given/when/then), 통합 테스트는 MockMvc + MySQL Testcontainers.

**Organization**: 유저 스토리별 독립 구현·검증. 공통 영속(엔티티·마이그레이션)은 Foundational 로 선행.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Flyway 마이그레이션 작성 — `app_version` 테이블 생성 + 초기 행 시드(min 1.0.0·latest 1.0.1, 스토어 링크는 확정 시 값/미확정 시 NULL) in `api/src/main/resources/db/migration/V2026.08.13.HH.mm.ss__app_version_table.sql` (파일 생성 시각으로 명명, data-model.md 의 DDL 준수)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 의존하는 영속 계층. 완료 전 스토리 구현 시작 금지.

- [X] T002 `AppVersion` 엔티티 작성(BaseEntity 상속, `min_supported_version`·`latest_version`·`ios_store_url`·`aos_store_url`, 연관관계 없음) in `common/src/main/kotlin/com/kbap/common/domain/appversion/model/AppVersion.kt`
- [X] T003 [P] `AppVersionRepository`(Spring Data JPA, `findTopByOrderByIdAsc`) in `common/src/main/kotlin/com/kbap/common/domain/appversion/AppVersionRepository.kt`
- [X] T004 `ModuleBoundaryTest` 의 `allowedDomainDeps` 에 `"appversion" to emptySet()` 추가 후 arch 태그 테스트 통과 확인 in `api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt`

**Checkpoint**: `./gradlew :api:test --tests "com.kbap.api.architecture.ModuleBoundaryTest"` 통과 + 기존 통합 테스트 기동 시 엔티티↔스키마 validate 통과

---

## Phase 3: User Story 1 - 구버전 사용자 강제 업데이트 유도 (Priority: P1) 🎯 MVP

**Goal**: 공개 GET 한 번으로 minSupportedVersion·latestVersion·storeUrls(ios·aos)를 BaseResponse 로 반환

**Independent Test**: `GET /api/app-version` 요청 하나로 시드 값(1.0.0/1.0.1)과 storeUrls(aos=null)가 반환되는지 확인

### Tests for User Story 1 (REQUIRED — 먼저 작성, Red 확인) ⚠️

- [ ] T005 [US1] 실패 통합 테스트 작성 — given(시드된 버전 정보) when(GET /api/app-version) then(200 + payload.minSupportedVersion/latestVersion/storeUrls.ios/storeUrls.aos=null, aos 는 필드 누락이 아닌 명시적 null) in `api/src/test/kotlin/com/kbap/api/appversion/AppVersionIntegrationTest.kt` — 실행해 Red(404) 확인

### Implementation for User Story 1

- [X] T006 [US1] 공개 조회 구현 — `AppVersionResponse`(storeUrls 중첩 구조)·`AppVersionService`(@Transactional(readOnly=true), 행 부재 시 BusinessException(INTERNAL_SERVER_ERROR))·`AppVersionApi`(swagger 인터페이스)·`AppVersionController`(GET `ApiPaths.API + "/app-version"`) in `api/src/main/kotlin/com/kbap/api/appversion/` — T005 Green 확인

**Checkpoint**: US1 통합 테스트 Green — MVP 성립

---

## Phase 4: User Story 2 - 로그인 전 접근 보장 (Priority: P2)

**Goal**: 인증 토큰 없이도 버전 조회가 성공한다 (JWT 필터 opt-in 구조에서 미등록 유지)

**Independent Test**: Authorization 헤더 없이 GET 요청 → 401 아닌 200

### Tests for User Story 2 (REQUIRED) ⚠️

- [ ] T007 [US2] 무인증 접근 테스트 추가 — given(인증 토큰 없음) when(GET /api/app-version) then(200 정상 응답, 401 아님) + `/api/app-version` 이 `WebConfig` JWT 필터 `addUrlPatterns` 에 등록되지 않았음을 고정 in `api/src/test/kotlin/com/kbap/api/appversion/AppVersionIntegrationTest.kt` — 실패 시에만 WebConfig 수정(등록 제거), US1 완료 상태면 즉시 Green 이 정상

**Checkpoint**: 무인증 시나리오 고정 — US1·US2 독립 검증 완료

---

## Phase 5: User Story 3 - 관리자의 버전 정보 갱신 (Priority: P3)

**Goal**: ADMIN 롤이 admin API 로 값을 갱신하면 즉시 공개 조회에 반영. 비관리자·형식 위반은 거부

**Independent Test**: 관리자 PUT 으로 latestVersion 1.0.2 변경 → 공개 GET 이 1.0.2 반환. 일반 회원 PUT → 403(AUTH-008)

### Tests for User Story 3 (REQUIRED — 먼저 작성, Red 확인) ⚠️

- [ ] T008 [US3] 실패 통합 테스트 작성 — ①관리자 PUT 후 공개 GET 에 반영 ②관리자 GET 200 ③일반 회원 PUT 403 AUTH-008 ④semver 형식 위반 400 COMMON-002 ⑤무토큰 401 (관리자/일반 회원 픽스처는 기존 admin 통합 테스트 패턴 재사용) in `api/src/test/kotlin/com/kbap/api/admin/AdminAppVersionIntegrationTest.kt` — 실행해 Red 확인

### Implementation for User Story 3

- [X] T009 [US3] `AppVersion.update(minSupportedVersion, latestVersion, iosStoreUrl, aosStoreUrl)` 도메인 메서드 추가 in `common/src/main/kotlin/com/kbap/common/domain/appversion/model/AppVersion.kt`
- [X] T010 [US3] admin 구현 — `AdminAppVersionUpdateRequest`(@field:NotBlank·@field:Pattern semver·@field:Size(512))·`AdminAppVersionService`(@Transactional, dirty checking — save() 호출 금지)·`AdminAppVersionApi`·`AdminAppVersionController`(GET·PUT `ApiPaths.ADMIN + "/app-version"`, WebConfig 무변경) in `api/src/main/kotlin/com/kbap/api/admin/` — T008 Green 확인

**Checkpoint**: 전 스토리 독립 검증 완료

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T011 전체 회귀 + quickstart 검증 — `./gradlew build` 통과 확인, 리팩터링(중복·네이밍 규약 `get~`/`update~` 점검), Kotlin 주석 0건 확인

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)** → **Foundational (P2)** → 유저 스토리(P3~P5, 우선순위순) → **Polish (P6)**
- T002 는 T001 과 독립 작성 가능하나, 통합 테스트 기동(validate)엔 T001 필요
- US2(T007)는 US1 엔드포인트 존재를 전제(테스트 대상 경로) — US1 뒤 진행
- US3 는 Foundational 만 있으면 US1 과 병렬 가능하나, 공개 GET 반영 검증(①)은 US1 완료 후 Green

### Parallel Opportunities

- T003 은 T002 완료 즉시 [P] (다른 파일)
- T005 와 T008 은 서로 다른 테스트 파일 — 팀 병렬 시 동시 작성 가능

## Implementation Strategy

**MVP = Phase 1~3** (T001~T006): 공개 조회 하나로 클라이언트 강제 업데이트 판단이 성립한다. 이후 US2(무인증 고정)·US3(관리자 갱신)를 증분 배달. 각 task 완료마다 커밋한다.
