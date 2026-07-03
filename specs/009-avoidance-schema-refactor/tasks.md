---

description: "Task list for 기피 성분 스키마 리팩터"
---

# Tasks: 기피 성분 데이터 구조 정리 — 미사용 분류 제거 + 다국어 저장 단순화

**Input**: Design documents from `/specs/009-avoidance-schema-refactor/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md (contracts/ 없음 — 외부 API 계약 무변경)

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 스토리는 구현 전에 실패 테스트(Kotest `BehaviorSpec`, 한국어 given/when/then)를 먼저 작성해 Red 를 확인한다.

**Organization**: 스토리별 그룹. US1(P1) 번역 JSON 통합 → US2(P2) 분류 카테고리 제거.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1 / US2
- 모든 경로는 worktree 루트(`meogo-server-009`) 기준

## ⚠️ 스토리 간 파일 결합 주의

이 기능은 순수 리팩터라 두 스토리가 일부 파일을 공유한다 — `AvoidanceSubstanceJpaEntity.kt`(US1 번역 매핑 → US2 `toDomain` 시그니처), `AvoidanceSubstanceRepositoryAdapterTest.kt`(US1 JSON 케이스 추가 → US2 카테고리 케이스 제거), `V6__...sql`(US1 번역 파트 → US2 category 테이블 DROP). 따라서 **US2 는 US1 완료 후 진행**(공유 파일 순차 편집). 각 스토리는 자체 체크포인트에서 빌드 Green 으로 독립 검증된다.

---

## Phase 1: Setup

**Purpose**: 리팩터 회귀 기준선 확보

- [ ] T001 [P] `./gradlew :core:avoidance:test :infra:persistence:test :app:api:test` 로 현재 baseline Green 확인(변경 전 기준선 기록)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리 착수 전 공유 선행 작업

이 리팩터는 **기존 배선 위에서** 이뤄져 새로 만들 블로킹 인프라가 없다. 별도 Foundational 태스크 없음.

**주의(구현 규칙)**: 엔티티 JSON 키 파싱은 `LanguageCode.from(code)`(미지정 시 `KO` 기본)를 쓰지 않는다 — 미지정 키가 `KO` 로 흡수되면 안 되므로 **엄격 code 매칭**(`LanguageCode.entries.firstOrNull { it.code == key }`)으로 매핑 불가 키는 **무시**한다.

**Checkpoint**: Setup 확인 완료 → US1 착수 가능

---

## Phase 3: User Story 1 - 다국어 번역 저장 방식 단순화 (Priority: P1) 🎯 MVP

**Goal**: `avoidance_substance` 의 언어별 컬럼 9종을 단일 `translations` JSON 컬럼으로 통합한다. `korean_name` 컬럼은 유지, JSON 은 비-`ko` 번역만 담는다. 도메인 `koreanName` + `translations: Map<LanguageCode,String>` 구조·`displayName` 동작은 불변.

**Independent Test**: 여러 언어 번역을 가진 성분을 JSON 으로 저장→조회했을 때 각 언어 `displayName(lang)` 이 저장값과 같고, 미보유 언어·빈 `{}` 는 `koreanName` 으로 폴백하면 성공. (이 단계에선 분류 카테고리는 아직 존재.)

### Tests for User Story 1 (Test-First: 먼저 작성·실패 확인) ⚠️

- [ ] T002 [P] [US1] 영속 JSON 왕복 실패 테스트 작성 in `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceRepositoryAdapterTest.kt` — given `translations`(JSON, 비-ko 다수) 를 가진 성분 저장 → when `findByCodes` 조회 → then 각 언어 `displayName(lang)`=저장값 / 미보유 언어=`koreanName` / `{}`=모든 비-ko 조회가 `koreanName`. Red 확인(`./gradlew :infra:persistence:test`).

### Implementation for User Story 1

- [ ] T003 [US1] `AvoidanceSubstanceJpaEntity` 를 JSON 번역 매핑으로 변경 in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceJpaEntity.kt` — `name_zh_hans`…`name_es` 9필드 제거, `@JdbcTypeCode(SqlTypes.JSON) @Column(name="translations") var translations: Map<String,String> = emptyMap()` 추가, `translationColumns()` 제거, `toDomain(categories)` 는 JSON 을 **엄격 code 매칭**으로 `Map<LanguageCode,String>` 복원(매핑 불가 키 무시). `categories` 파라미터는 US2 까지 유지.
- [ ] T004 [US1] V6 마이그레이션 생성(번역 파트) in `app/api/src/main/resources/db/migration/V6__drop_avoidance_category_and_jsonify_translations.sql` — (1) `ALTER TABLE avoidance_substance ADD COLUMN translations JSON NULL;` (2) 기존 `name_*` → `translations` 백필: 비-NULL 언어만 포함하는 JSON 객체(키=`en`,`ja`,`zh-Hans`,`zh-Hant`,`vi`,`id`,`th`,`ru`,`es`), 전부 NULL 이면 `{}`. (3) `name_zh_hans … name_es` 9개 컬럼 DROP. (V5 는 절대 수정 금지.)
- [ ] T005 [US1] US1 Green 확인: `./gradlew :infra:persistence:test --tests "*AvoidanceSubstanceRepositoryAdapterTest*"` — JSON 왕복·폴백 통과(카테고리 기능은 아직 존재, 빌드 Green).

**Checkpoint**: 번역이 JSON 단일 컬럼으로 저장·조회되며 기존 이름 조회 결과 동일. `avoidance_substance` 에 `name_*` 컬럼 없음. (분류는 아직 존재 — 독립 배포 가능한 증분.)

---

## Phase 4: User Story 2 - 미사용 분류 카테고리 제거 (Priority: P2)

**Goal**: `AvoidanceCategory` 및 관련 데이터·코드·아키텍처 규칙을 완전히 제거한다. 관찰 동작·API 무변경.

**Independent Test**: 분류 카테고리가 코드·스키마에서 사라진 뒤에도 `findByCodes`·`findByIngredientIds` 로 전 성분이 복원되고 전체 빌드·테스트가 Green 이면 성공.

**Depends on US1**: 공유 파일(`AvoidanceSubstanceJpaEntity`, `AvoidanceSubstanceRepositoryAdapterTest`, `V6__...sql`) 순차 편집 때문에 US1 완료 후 진행.

### Tests for User Story 2 (Test-First: 먼저 작성·실패 확인) ⚠️

- [ ] T006 [P] [US2] 도메인 카테고리 제거 실패 테스트 in `core/avoidance/src/test/kotlin/com/meogo/core/avoidance/AvoidanceSubstanceTest.kt` — `categories`/`belongsTo`/`AvoidanceCategory.entries` 참조 제거, `reconstitute(...)`(categories 없이) 구성, `displayName` 폴백 회귀 유지. (도메인 변경 전까지 컴파일 Red.)
- [ ] T007 [P] [US2] "전 성분 복원" 실패 테스트 보강 in `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceRepositoryAdapterTest.kt` — 카테고리 매핑 없이도 `findByCodes` 가 성분을 복원(현재 Reconstitutor drop 로 실패)함을 검증, `saveMembership`/`byCategory` 케이스 제거.

### Implementation for User Story 2

- [ ] T008 [US2] 도메인 `AvoidanceSubstance` 수정 in `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceSubstance.kt` — `categories` 필드·`belongsTo`·`require(categories …)` 제거, `reconstitute` 시그니처에서 `categories` 제거.
- [ ] T009 [P] [US2] `AvoidanceCategory.kt` 삭제 in `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceCategory.kt`
- [ ] T010 [US2] port 에서 `byCategory` 제거 in `core/avoidance/src/main/kotlin/com/meogo/core/avoidance/AvoidanceSubstanceRepository.kt` (`findByCodes` 유지)
- [ ] T011 [US2] `AvoidanceSubstanceJpaEntity.toDomain` 에서 `categories` 파라미터 제거 in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceJpaEntity.kt` (T003 결과 위에서 수정)
- [ ] T012 [US2] `AvoidanceSubstanceReconstitutor` 수정 in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceReconstitutor.kt` — `categoryJpaRepository` 의존 제거, 카테고리 조인·**"카테고리 없으면 drop" 필터 제거**(rows 만으로 `toDomain()` 복원).
- [ ] T013 [US2] `AvoidanceSubstanceRepositoryAdapter` 수정 in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceRepositoryAdapter.kt` — `byCategory` 구현·`avoidanceSubstanceCategoryJpaRepository` 생성자 의존 제거.
- [ ] T014 [P] [US2] `AvoidanceSubstanceCategoryJpaEntity.kt` + `AvoidanceSubstanceCategoryJpaRepository.kt` 삭제 in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/`
- [ ] T015 [US2] V6 에 `DROP TABLE avoidance_substance_category;` 추가 in `app/api/src/main/resources/db/migration/V6__drop_avoidance_category_and_jsonify_translations.sql` (T004 파일에 이어서 — 인입 FK 없어 안전)
- [ ] T016 [P] [US2] ArchUnit `ModuleBoundaryTest` 의 "영속 avoidance 엔티티의 분류 저장 형식 회귀" given 블록 제거 in `app/api/src/test/kotlin/com/meogo/app/api/architecture/ModuleBoundaryTest.kt` (`AvoidanceSubstanceCode` label-only 검증 given 은 **유지**)
- [ ] T017 [P] [US2] `FoodAvoidanceSubstanceResolverTest` 의 `categories`/`AvoidanceCategory` 픽스처 제거 in `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/FoodAvoidanceSubstanceResolverTest.kt`
- [ ] T018 [US2] US2 Green 확인: `./gradlew :core:avoidance:test :infra:persistence:test :app:api:test` — 도메인·영속·ArchUnit·application 통과.

**Checkpoint**: 분류 카테고리가 코드·스키마에서 완전히 제거되고 전 성분 복원·API 무변경.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [ ] T019 [P] 잔여 참조 점검 — `grep -rn "AvoidanceCategory\|byCategory\|belongsTo\|avoidance_substance_category" core infra application app --include="*.kt" --include="*.sql"` 결과가 V5 시드 INSERT 외 0건인지 확인(quickstart §2)
- [ ] T020 전체 빌드 회귀 게이트: `./gradlew build` Green (SC-003)
- [ ] T021 [P] `docs/architecture` 등에 avoidance 분류 언급이 있으면 정리(있을 때만; CLAUDE.md 플랜 포인터는 이미 009 로 갱신됨)
- [ ] T022 quickstart.md 검증 절차 수행 + V5 무변경 확인(`git diff -- app/api/src/main/resources/db/migration/V5__*.sql` 빈 결과)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)**: 즉시 시작
- **Foundational(P2)**: 태스크 없음(리팩터)
- **US1(Phase 3)**: Setup 후 시작 — MVP
- **US2(Phase 4)**: **US1 완료 후**(공유 파일 순차 편집)
- **Polish(Phase 5)**: US1+US2 완료 후

### Within US1

- T002(test, Red) → T003·T004(impl) → T005(Green). T003·T004 는 서로 다른 파일이나 동일 스토리 검증 대상이라 함께 완료 후 T005.

### Within US2

- 테스트 T006·T007(Red) 먼저 → 구현 T008–T017 → T018(Green).
- 도메인(T008/T010) 변경이 엔티티/어댑터(T011/T013) 컴파일에 선행. T009·T014·T016·T017 은 독립 파일이라 병렬 가능.
- T011·T012·T013·T015 는 US1 의 T003·T004 산출물 위에서 수정.

### Parallel Opportunities

- US1 테스트 T002 는 단일.
- US2 테스트 T006·T007 병렬(다른 테스트 파일).
- US2 구현 중 T009(enum 삭제)·T014(엔티티/리포 삭제)·T016(ArchUnit)·T017(application 테스트)은 병렬.
- Polish T019·T021 병렬.

---

## Parallel Example: User Story 2

```bash
# US2 실패 테스트 먼저(병렬):
Task: "도메인 카테고리 제거 테스트 in core/avoidance/.../AvoidanceSubstanceTest.kt"
Task: "전 성분 복원 테스트 in infra/persistence/.../AvoidanceSubstanceRepositoryAdapterTest.kt"

# US2 독립 파일 정리(병렬):
Task: "AvoidanceCategory.kt 삭제"
Task: "Category 엔티티/리포지토리 삭제"
Task: "ArchUnit 분류 given 블록 제거"
Task: "FoodAvoidanceSubstanceResolverTest categories 픽스처 제거"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup(baseline Green) → 2. US1(T002→T003→T004→T005) → 3. **STOP & VALIDATE**: 번역 JSON 왕복·폴백 Green, `name_*` 컬럼 제거. 분류는 아직 존재하나 독립적으로 배포 가능한 증분.

### Incremental Delivery

1. US1 완료(번역 저장 단순화) → 독립 검증
2. US2 완료(분류 제거) → 독립 검증
3. Polish(잔여 grep 0건 + `./gradlew build` + quickstart)

---

## Notes

- [P] = 다른 파일·무의존. 공유 파일(엔티티·adapter 테스트·V6)은 [P] 아님.
- Test-First: 각 스토리 구현 전 실패 테스트 확인(원칙 I).
- Kotlin 주석 금지·도메인 불변(모든 상태 `val`, `private copy`)·`BaseResponse`/`/api/v` 규약 준수.
- V5 불변, V6 forward-only. 테스트(H2)는 엔티티 매핑으로 스키마 생성(Flyway off).
- 각 태스크/논리 그룹 후 커밋.
