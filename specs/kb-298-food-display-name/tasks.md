# Tasks: 음식 표시용 이름(display name) 분리

**Input**: Design documents from `/specs/kb-298-food-display-name/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api-responses.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 원칙 I) — 각 task 는 실패 테스트 작성·Red 확인 → 최소 구현(Green) → Refactor 순으로 진행한다. 테스트는 Kotest `BehaviorSpec`(한국어 given/when/then).

**Organization**: user story 단위 phase. 모든 경로는 워크트리 루트(`.claude/worktrees/kb-298-food-display-name/`) 기준 상대 경로.

## Phase 1: Setup — 스키마·엔티티 기반

**Purpose**: `display_name` 컬럼과 엔티티 프로퍼티 — 모든 스토리의 공통 전제

- [X] T001 Flyway 마이그레이션 생성 `api/src/main/resources/db/migration/V<생성시각 timestamp>__add_food_display_name.sql` — `ALTER TABLE food ADD COLUMN display_name VARCHAR(255) NOT NULL DEFAULT '' AFTER korean_name;` + `UPDATE food SET display_name = korean_name WHERE display_name = '';` (파일명은 생성 시점 로컬 시각, data-model.md 참조)
- [X] T002 [실패 테스트 먼저] `common/src/test/kotlin/com/kbap/common/domain/food/model/FoodTest.kt` 에 추가 — `Food.incomplete(koreanName, displayName)` 2인자 시그니처: displayName blank/255자 초과 시 예외, 정상 생성 시 두 값 보존, `displayName(KO)` 가 표시명 반환, displayName 빈 값이면 koreanName 폴백. Red 확인 후 `common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt` 구현 — `displayName` 프로퍼티(`@Column(name = "display_name", nullable = false, length = 255)`), `incomplete` 확장, `localizedName()` ko 베이스를 `displayName.ifBlank { koreanName }` 로 교체, `koreanName()` 액세서 삭제(컴파일 에러 지점은 후속 task 에서 소비처별 교체)

**Checkpoint**: `./gradlew :common:test --tests "*FoodTest*"` Green. 전체 컴파일은 소비처 교체 전이라 깨질 수 있음 — T003~ 에서 순차 해소.

---

## Phase 2: Foundational — 영속·도메인 서비스 쓰기 경로

**Purpose**: 적재·조회의 공통 통로. US1·US2 가 모두 의존

- [X] T003 [실패 테스트 먼저] `api/src/test/kotlin/com/kbap/api/food/` 통합 스펙(기존 repository 스펙 위치에 맞춰) — `upsertIncomplete` 가 display_name 을 저장하고, 같은 korean_name 재삽입 시 기존 display_name 을 유지(first-write-wins). Red 확인 후 `common/src/main/kotlin/com/kbap/common/domain/food/FoodRepositoryCustomImpl.kt` 의 insert 컬럼·파라미터에 `display_name` 추가 (`on duplicate key update id = id` 유지)
- [X] T004 `common/src/main/kotlin/com/kbap/common/domain/food/FoodService.kt` — `createIncomplete`/`upsertAndResolve` 시그니처를 match key→원본 표기 맵(`Map<String, String>` 또는 동등)으로 확장해 `Food.incomplete(matchKey, 원본)` 으로 적재. `getDetail` 의 `koreanName = food.koreanName().takeIf {...}` → `food.displayName.takeIf { it != foodName }` 교체 (T003 스펙과 같은 사이클에서 Red→Green)
- [X] T005 [P] `common/src/main/kotlin/com/kbap/common/domain/food/dto/FoodSummaryView.kt` — `koreanName = food.koreanName().takeIf {...}` → `food.displayName.takeIf { it != localizedName }` 교체 (기존 목록/북마크/홈 스펙이 커버 — 스펙의 기대값을 표시명 기준으로 갱신하며 Red 확인)

**Checkpoint**: `:common` 컴파일 통과 + food 도메인 스펙 Green

---

## Phase 3: User Story 1 — 스캔 신규 적재 원본 표기 보존 (P1) 🎯 MVP

**Goal**: DB miss 적재 음식이 원본 표기(띄어쓰기 포함)를 display_name 으로 보존하고, 스캔 응답이 그 값을 노출. 중복 판정은 match key 로 불변

**Independent Test**: 미등록 "들깨 칼국수" 스캔 → 응답 `koreanName`="들깨 칼국수", DB `korean_name`="들깨칼국수" · "들깨칼국수" 재스캔 → 신규 행 0건

- [X] T006 [US1] [실패 테스트 먼저] `api/src/test/kotlin/com/kbap/api/scan/` 기존 스캔 스펙에 추가 — (1) miss 적재 시 food.displayName=추출 원본·koreanName=matchKey, (2) 표기만 다른 재스캔 시 신규 행 없음·기존 displayName 유지, (3) 스캔 응답 items[].koreanName 이 원본 표기, (4) matched 음식 응답 koreanName 이 그 음식의 displayName. Red 확인
- [X] T007 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt` — `resolveFoods` 에서 matchKey→원본 표기(첫 등장 우선) 맵을 만들어 `foodService.createIncomplete` 에 전달, 응답 조립의 `food!!.koreanName()` → `food!!.displayName` 교체. T006 Green 확인
- [X] T008 [P] [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanApi.kt` — swagger 설명의 "표준 한국어명" 문구를 "표시용 한국어명(원본 표기)" 로 갱신 (contracts/api-responses.md 참조, 스키마 불변)

**Checkpoint**: US1 독립 테스트 통과 — MVP 배포 가능 상태

---

## Phase 4: User Story 2 — 기존 조회 전체 표시명 응답 (P2)

**Goal**: 음식 이름을 노출하는 나머지 경로(검색·관리자·이미지 프롬프트·batch LLM) 전부 표시명 사용. 응답 필드 구조 무변경

**Independent Test**: contracts/api-responses.md 의 표 전수 — 각 응답 필드 값이 displayName 기준인지 스펙으로 확인

- [X] T009 [US2] [실패 테스트 먼저] `api/src/test/kotlin/com/kbap/api/food/FoodSearchControllerTest.kt` 에 추가 — 표시명 "김치 찌개"(korean_name "김치찌개") 시드 후 `keyword=김치 찌개`(공백 포함)와 `keyword=김치찌개` 모두 히트. Red 확인 후 `common/src/main/kotlin/com/kbap/common/domain/food/FoodService.kt` `getFoodsByKeyword` KO 분기에서 키워드를 `KoreanMenuNameNormalizer.matchKey` 로 정규화(정규화 결과 blank 면 원 키워드 유지)
- [X] T010 [US2] [실패 테스트 먼저] `api/src/test/kotlin/com/kbap/api/admin/` 기존 관리자 스펙에 추가 — (1) 음식명 수정 시 display_name=입력·korean_name=matchKey(입력) 재정규화·중복 검사 match key 기준, (2) 시드 등록 시 원본 표기 보존, (3) 목록/상세/검수 응답 koreanName 값이 displayName. Red 확인 후 `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt`(updateFood·seedIncomplete·응답 매핑 3곳)·`AdminFoodContentReviewResponse.kt` 구현
- [X] T011 [P] [US2] `api/src/main/kotlin/com/kbap/api/food/FoodImageBatchCollectService.kt` — 이미지 프롬프트 이름 조회 `?.koreanName` → `?.displayName` 교체 (기존 스펙 기대값 갱신으로 Red→Green)
- [X] T012 [P] [US2] [실패 테스트 먼저] `batch/src/test/kotlin/com/kbap/batch/content/` 기존 프로세서 스펙 기대값을 표시명 기준으로 갱신(페이크 클라이언트 호출 인자 검증) — Red 확인 후 `batch/src/main/kotlin/com/kbap/batch/content/FoodContentItemProcessor.kt` 3곳 `food.koreanName` → `food.displayName` 교체

**Checkpoint**: contracts 표 전수 반영 완료 — `koreanName()`/`food.koreanName` 표시 경로 잔존 0건 (`grep -rn "koreanName" */src/main` 으로 확인, match key 용도만 남아야 함)

---

## Phase 5: User Story 3 — 기존 데이터 이관 검증 (P3)

**Goal**: 백필 완결성 — 표시명 빈 음식 0건, 기존 음식 조회값 불변

**Independent Test**: 마이그레이션 적용된 Testcontainers DB 에서 display_name='' 행 0건

- [X] T013 [US3] [실패 테스트 먼저] `api/src/test/kotlin/com/kbap/api/food/` 통합 스펙 — Flyway 적용 후 시드/기존 행의 `display_name = korean_name` 백필 확인 + 빈 표시명 0건 + 백필 행 조회 응답이 이전과 동일 이름. Red 는 T001 마이그레이션의 UPDATE 를 주석 처리한 상태가 아니라 **테스트 먼저 작성 후 T001 파일 존재로 즉시 Green 이면 백필 누락 케이스(DEFAULT '' 만 있는 행)를 시드로 만들어 폴백 검증**으로 Red 를 만든다

**Checkpoint**: 이관 완결성 스펙 Green

---

## Phase 6: Polish & Cross-Cutting

- [X] T014 `./gradlew build` 전체 Green 확인 — raw `insert into food` 시드 파일들(AdminControllerTest·BookmarkControllerTest·CommunityControllerTest·FoodTestSeed 등)의 깨짐 여부 전수 확인, 표시명 검증이 필요한 시드에만 `display_name` 명시 (메모리: 전체 build 로만 잡히는 유형)
- [X] T015 [P] `specs/kb-298-food-display-name/quickstart.md` 검증 결과 기록 — 스캔 원본 표기·재스캔 무중복·공백 검색 3종을 **통합 테스트로 검증**(앱 수동 실행은 미수행, quickstart 에 근거 테스트 명시)

---

## Dependencies & Execution Order

```text
Phase 1 (T001 스키마, T002 엔티티)
  → Phase 2 (T003 upsert → T004 FoodService, T005[P] SummaryView)
    → Phase 3 US1 (T006 Red → T007 Green, T008[P] 문서)   🎯 MVP
    → Phase 4 US2 (T009 검색, T010 관리자, T011[P] 이미지, T012[P] batch — 상호 독립)
    → Phase 5 US3 (T013 — T001 에만 의존, US1/US2 와 병렬 가능)
      → Phase 6 (T014 전체 빌드, T015[P] 수동 확인)
```

- US1 이 MVP — Phase 3 완료 시점에 배포 가능.
- US2 의 T009~T012 는 서로 다른 파일이라 병렬 가능. US3(T013)은 Phase 2 완료 후 언제든 착수 가능.
- 커밋은 task(논리 단위)마다.

## Implementation Strategy

1. **MVP first**: Phase 1→2→3 으로 스캔 경로부터. 여기까지가 사용자 체감의 대부분.
2. **Incremental**: Phase 4 는 응답 교체 성격이라 task 단위로 나눠 리뷰 부담 최소화.
3. **회귀 방어**: 각 task 의 기존 스펙 기대값 갱신은 "정규화명 → 표시명" 교체를 의도적으로 드러내는 diff 로 남긴다(리뷰에서 값 출처 변경이 보이도록).
