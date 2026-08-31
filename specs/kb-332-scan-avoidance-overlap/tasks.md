# Tasks: v2 스캔 응답 기피성분 겹침 표시

**Input**: Design documents from `/specs/kb-332-scan-avoidance-overlap/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/scan-v2-avoidances.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 원칙 I) — 각 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다. 테스트는 Kotest BehaviorSpec(given/when/then 한국어).

**Organization**: 스토리별 독립 구현·검증. DB·마이그레이션·신규 의존성 없음 → Setup/Foundational 단계 해당 없음.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

해당 없음 — 기존 모듈 구조·의존성 재사용(신규 파일 1개, 수정 4개 파일뿐).

## Phase 2: Foundational

해당 없음 — 모든 작업이 US1·US2 에 귀속된다.

---

## Phase 3: User Story 1 - 매칭된 음식의 기피성분 겹침 확인 (Priority: P1) 🎯 MVP

**Goal**: v2 스캔 응답의 매칭 항목마다 회원 기피성분 전체를 `{code, name(lang 번역), overlapped, riskLevel}` 로 나열. 프로필 없는 회원(게스트)은 `avoidances = null`.

**Independent Test**: 기피성분 등록 회원으로 v2 스캔 → 매칭 항목의 avoidances 가 성분 데이터·카탈로그 번역과 일치. 게스트 회원 → 전 항목 null.

- [x] T001 [US1] **Red**: `Food.overlappedIngredients(avoidedCodes)` 실패 스펙 작성 — 겹침 성분 반환(위험도 정보 보존)·미겹침 제외·READY 아님/성분 null 이면 빈 목록. 파일: `common/src/test/kotlin/com/kbap/common/domain/food/model/FoodOverlappedIngredientsTest.kt` (기존 `FoodOverallRiskTest.kt` 픽스처 스타일 참조). 컴파일 실패가 아닌 assertion Red 확인을 위해 메서드 스텁 없이 먼저 작성 → 컴파일 에러도 Red 로 인정.
- [x] T002 [US1] **Green**: `Food.overlappedIngredients` 구현 — `common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt` (`overallRisk` 옆, 동일 데이터 소스). `:common:test` 통과 확인.
- [x] T003 [US1] **Red**: v2 스캔 통합 스펙 추가 — `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 에 시나리오 4건: ① 겹침 성분 `overlapped=true`+`riskLevel`(포함 확률 기반 SAFE/CAUTION/DANGER — 음식 상세와 동일 규칙) ② 미겹침 성분 `overlapped=false`+`riskLevel=null` ③ `name` 이 요청 lang 번역(부재 시 ko)으로 해석 ④ 프로필 없는 게스트 회원 → `avoidances=null`. Red 확인.
- [x] T004 [P] [US1] `ScanResult.ItemRiskResult` 에 `avoidances: List<AvoidanceOverlap>? = emptyList()` + `AvoidanceOverlap(code, name, overlapped, riskLevel)` 추가 — `api/src/main/kotlin/com/kbap/api/scan/ScanResult.kt`
- [x] T005 [P] [US1] `ScanV2Response.ItemRiskResponse` 에 `avoidances` 필드 + `AvoidanceOverlapResponse` + `@Schema` 문서(계약: contracts/scan-v2-avoidances.md 의 필드 규약 문구) 추가, `from` 매핑 — `api/src/main/kotlin/com/kbap/api/scan/ScanV2Response.kt` (v1 `ScanResponse.kt` 는 손대지 않음)
- [x] T006 [US1] **Green**: `ScanService` 조립 구현 — `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt`: 이미 조회한 `Member` 의 profile null 이면 전 항목 `avoidances=null`; 아니면 `IngredientJpaRepository.findByCodeIn`(스캔당 1건)으로 카탈로그 로드 후 기피성분 전체를 enum 선언 순서로 `{code, displayName(lang), overlapped, riskLevel}` 매핑(겹침 판정은 `food.overlappedIngredients`). `:api:test` 통과 확인.

**Checkpoint**: US1 만으로 배포 가능한 MVP — 매칭 항목 겹침 표시 + 게스트 null.

---

## Phase 4: User Story 2 - 미매칭 항목의 일관된 표시 (Priority: P2)

**Goal**: 미매칭(matched=false)·degraded 항목은 `avoidances = []` (겹침 판정 불가 — riskLevel UNKNOWN 과 일관). similarFood 가 있어도 대체 판정하지 않음.

**Independent Test**: 매칭+미매칭 혼재 스캔 결과에서 미매칭 항목만 빈 목록인지 확인.

- [x] T007 [US2] **Red**: 통합 스펙 추가 — `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt`: ① 미매칭 항목(프로필 보유 회원) `avoidances=[]` — similarFood 존재 케이스 포함 ② 기피성분 0개 회원의 매칭 항목 `avoidances=[]`. Red 확인(T006 구현이 이미 충족하면 Green 시작도 허용 — 그 경우 회귀 고정 스펙으로 남긴다).
- [x] T008 [US2] **Green**: 미매칭 분기 확인·보완 — `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt`. `:api:test` 통과 확인.

**Checkpoint**: 계약(contracts/scan-v2-avoidances.md)의 null/[]/목록 3상태 전부 검증됨.

---

## Phase 5: Polish & Cross-Cutting

- [x] T009 [P] **Refactor**: `Food.overallRisk` 가 `overlappedIngredients` 를 재사용하도록 중복 필터 정리(동작 불변 — 기존 `FoodOverallRiskTest` 그대로 통과) — `common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt`
- [x] T010 전체 검증: `./gradlew build` (ArchUnit `arch` 태그 포함) + v1 스캔 응답 무변경 확인(기존 `ScanControllerTest` v1 시나리오 통과가 근거)

---

## Dependencies

```text
US1: T001 → T002 → T003 → (T004 ∥ T005) → T006
US2: T007 → T008   (US1 완료 후 — 동일 파일(ScanService·ScanControllerTest) 수정이라 순차)
Polish: T009 (T002 이후 아무 때나) → T010 (마지막)
```

## Parallel Opportunities

- T004 ∥ T005 (다른 파일, 타입 추가만)
- T009 는 US2 와 병렬 가능(`:common` vs `:api`)

## Implementation Strategy

- **MVP = US1** (T001~T006): 매칭 항목 겹침 표시 + 게스트 null — 이것만으로 스토리 가치 전달.
- US2 는 조립 분기 1곳 + 회귀 고정 스펙 — 소규모 증분.
- 커밋 단위: 스토리별 Red→Green 완료 시점(T006 후, T008 후, T010 후).
