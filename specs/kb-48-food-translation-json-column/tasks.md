---
description: "Task list — 음식 번역결과 JSON 칼럼 통합 (별도 테이블 → food 행 JSON 2칼럼)"
---

# Tasks: 음식 번역결과 JSON 칼럼 통합 (KB-48)

**Input**: Design documents from `specs/kb-48-food-translation-json-column/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-detail-api.md, quickstart.md

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 층 구현 전에 실패 테스트(Kotest `BehaviorSpec`, given/when/then 한국어)를 먼저 작성·Red 확인한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1(P1 상세조회 응답 동일) · US2(P2 무손실 이행) · US3(P3 레거시 테이블 제거)

> **실행 순서 주의(리팩터 특성)**: `FoodRepository` 포트에서 번역 조회 메서드 2종을 제거하면(Foundational) `:infra:persistence`(adapter)·`:application:client`(usecase) 컴파일이 함께 깨진다. 따라서 단계는 **컴파일 안전 순서**로 배치한다: Setup → Foundational(`:core:food`) → **US1(영속·유스케이스·web 복구 = 저장 원천 교체·계약 동결)** → US2(마이그레이션 백필) → US3(테이블 DROP·레거시 파일 삭제) → Polish. 우선순위(P1→P2→P3)와 실행 순서가 일치한다. 각 단계는 독립 검증 가능하다. **마이그레이션(V10)은 테스트에서 실행되지 않으므로**(H2·flyway off) US2·US3 은 로컬 docker MySQL DROP+CREATE 부팅으로 검증한다.

---

## Phase 1: Setup

- [X] T001 다음 Flyway 버전이 `V10` 으로 비어 있는지 확인한다(`app/api/src/main/resources/db/migration/` 최신 = `V9`). 병행 브랜치가 `V10` 을 선점했으면 본 마이그레이션을 다음 빈 번호로 재넘버링한다(내용 동일).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 의존하는 `:core:food` 도메인 형태를 확정한다 — `FoodContent` 가 번역 맵·폴백을 품고, `FoodRepository` 는 번역 조회 포트를 버린다. 완료 시 `:core:food` 단위 테스트가 그린이 되고, 하위 모듈은 US1 에서 재컴파일된다.

- [X] T002 [P] `core/food/src/test/kotlin/com/meogo/core/food/FoodContentTest.kt` 에 실패 테스트 작성/보강(Red): `name(lang)`·`description(lang)` 폴백 — (a) `KO` → 원문(`koreanName`·`description`), (b) 번역 존재 언어(예: `EN`) → 해당 번역값, (c) 지원 언어이나 맵에 없음(예: `RU`) → 원문 폴백, (d) 빈 번역 맵 → 원문. `ko` 키는 맵에 없음 전제.
- [X] T003 `core/food/src/main/kotlin/com/meogo/core/food/FoodContent.kt` 수정(Green): `nameTranslations`·`descriptionTranslations: Map<LanguageCode, String>`(기본 `emptyMap()`) 필드 추가 + `name(lang)`/`description(lang)` 메서드(`lang==KO` → 원문, else `map[lang] ?: 원문`). 기존 `koreanName`·`description` 검증(blank·255) 유지. `import com.meogo.core.kernel.lang.LanguageCode`. avoidance 미참조.
- [X] T004 [P] `core/food/src/main/kotlin/com/meogo/core/food/FoodRepository.kt` 수정: `findFoodNameTranslation(foodId, lang)`·`findFoodDescriptionTranslation(foodId, lang)` 시그니처 제거(`findByKoreanName` 만 남김). `LanguageCode` import 가 미사용이면 제거.

**Checkpoint**: `./gradlew :core:food:test` 그린. `:infra:persistence`·`:application:client`·`:app:api` 는 아직 미컴파일(US1 에서 해결).

---

## Phase 3: User Story 1 - 상세조회 응답이 저장 방식 교체 후에도 동일 (Priority: P1)

**Goal**: 번역을 `food` 행 JSON 칼럼(`name_translations`·`description_translations`)에 두고 음식 애그리거트와 함께 로드해, 상세조회가 언어별 추가 조회 없이 이전과 **동일한 음식명·설명·폴백**을 반환한다.

**Independent Test**: H2 통합에서 음식에 번역 맵을 저장 후 `findByKoreanName` 이 fetch join 1회로 `FoodContent` 번역 맵을 복원하고, `GetFoodDetailUseCase`·web 계약 응답이 (번역 존재/부재/미지정/미지원) 전 케이스에서 기존과 동일함을 확인.

### Tests (Test-First — 먼저 작성·Red 확인) ⚠️

- [X] T005 [P] [US1] `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapterTest.kt` 수정(Red): 시딩을 번역 테이블 삽입 → **`FoodJpaEntity.nameTranslations`·`descriptionTranslations`(String 키 맵) 설정**으로 교체. 검증: (a) `findByKoreanName` 이 `FoodContent.nameTranslations`/`descriptionTranslations`(`LanguageCode` 키)로 라운드트립 복원, (b) fetch join 1회로 번역까지 로드(번역 테이블 대상 별도 SELECT 없음), (c) 맵에 없는 언어 → `name(lang)`/`description(lang)` 원문 폴백, (d) 미지의 JSON 키는 복원 시 무시. 구 `findFoodNameTranslation`/`findFoodDescriptionTranslation` 기대 제거.
- [X] T006 [P] [US1] `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCaseTest.kt` 수정(Red): 번역 리포지토리 mock 호출 기대 제거. `FoodRepository.findByKoreanName` 가 번역 맵을 품은 `Food` 를 돌려주도록 스텁하고, 결과의 `name`·`description` 이 (번역 존재 언어 → 번역값, 번역 부재/`KO` → 원문) 도메인 폴백으로 해석됨을 검증. 미지원 언어 → `LanguageResolver`/`LanguageCode.from` 400 경로 불변.

### Implementation

- [X] T007 [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodJpaEntity.kt` 수정: `@JdbcTypeCode(SqlTypes.JSON) @Column(name="name_translations", nullable=false) var nameTranslations: Map<String,String> = emptyMap()` 와 동일 형태의 `descriptionTranslations`(`description_translations`) 추가. `toDomain()` 이 `FoodContent(koreanName, description, nameTranslations=resolve(nameTranslations), descriptionTranslations=resolve(descriptionTranslations))` 조립. `resolve(raw): Map<LanguageCode,String>` 는 `AvoidanceSubstanceJpaEntity.resolveTranslations` 와 동일(미지의 키 무시). import `org.hibernate.annotations.JdbcTypeCode`·`org.hibernate.type.SqlTypes`·`LanguageCode`. (T005 의존)
- [X] T008 [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapter.kt` 수정: 생성자에서 `foodNameTranslationJpaRepository`·`foodDescriptionTranslationJpaRepository` 의존 제거, `findFoodNameTranslation`/`findFoodDescriptionTranslation` 오버라이드 삭제. `findByKoreanName` 만 유지(기존 fetch join 쿼리 그대로 — 번역은 food 행에 포함). (T007 Green, T005 Green)
- [X] T009 [US1] `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt` 수정: `resolveFoodName`/`resolveDescription` 을 `food.content.name(lang)`/`food.content.description(lang)` 호출로 교체하고 `foodRepository.findFoodNameTranslation`/`findFoodDescriptionTranslation` 호출·헬퍼 제거. 나머지(기피성분 표시명·mock 위험도·`GetFoodDetailResult` 조립) 불변. (T006 Green, T008 의존)
- [X] T010 [US1] `app/api/src/test/kotlin/com/meogo/app/api/food/FoodTestSeed.kt` 수정: 음식 시드가 번역 테이블 행 대신 `FoodJpaEntity.nameTranslations`·`descriptionTranslations`(String 키 = `LanguageCode.code`, `ko` 제외)로 번역을 심도록 교체. 심는 언어·값은 기존과 동일하게 유지(계약 회귀 보존). (T007 의존)
- [X] T011 [US1] web 계약 회귀 확인: `./gradlew :app:api:test` 로 `FoodDetailControllerTest`·`FoodDetailLangTest`·`FoodDetailDescriptionTest` 가 **어서션 수정 없이**(시드 경로만 T010 으로 교체된 채) 그린임을 확인(FR-005·SC-001). 실패 시 원천 교체가 값을 바꾼 것이므로 구현을 교정한다.

**Checkpoint**: `./gradlew :core:food:test :infra:persistence:test :application:client:test :app:api:test` 전부 그린. 전 모듈 재컴파일 완료, 상세조회 응답 계약 동결 검증. (마이그레이션은 아직 미작성 — 다음 단계.)

---

## Phase 4: User Story 2 - 기존 번역 데이터가 무손실 이행 (Priority: P2)

**Goal**: 운영 DB 의 `food_name_translation`·`food_description_translation`(ACTIVE) 전 데이터를 `food` 행의 두 JSON 칼럼으로 한 건도 빠짐없이 백필한다.

**Independent Test**: 로컬 docker MySQL 에서 이행 전 `(food_id, lang_code, 값)` 스냅샷을 캡처하고, V10 적용 후 각 음식의 `JSON_KEYS`/값이 스냅샷과 정확히 일치(누락·변형·중복 0)함을 대조.

- [X] T012 [US2] `app/api/src/main/resources/db/migration/V10__jsonify_food_translations.sql` 신규(백필까지): (1) `ALTER TABLE food ADD COLUMN name_translations JSON NULL, ADD COLUMN description_translations JSON NULL;` (2) `UPDATE food SET name_translations=JSON_OBJECT(), description_translations=JSON_OBJECT();` (3) `food_name_translation`(`WHERE status='ACTIVE' AND name <> ''`)에서 `JSON_OBJECTAGG(lang_code, name)` 를 `food_id` 로 집계해 `food` 에 조인 UPDATE; `food_description_translation`(`WHERE status='ACTIVE' AND content <> ''`) → `description_translations` 동일; (4) `ALTER TABLE food MODIFY COLUMN name_translations JSON NOT NULL, MODIFY COLUMN description_translations JSON NOT NULL;`. **DROP 문은 US3 T014 에서 이 파일에 이어 붙인다.** 소프트삭제 정합 위해 백필은 ACTIVE 만, **빈 문자열 값은 제외해 폴백 대상이 되도록 키를 만들지 않는다**(FR-003, V6 선례의 `<> ''` 가드 답습).
- [X] T013 [US2] 로컬 docker MySQL 로 백필 검증(quickstart §2): DB DROP+CREATE 후 앱 부팅(V1→V10, DROP 제외 상태로 임시 검증하거나 T014 완료 후 통합 검증). 이행 전 번역 쌍 스냅샷과 이행 후 `SELECT id, JSON_KEYS(name_translations), JSON_KEYS(description_translations) FROM food` 및 값이 일치, 번역 0건 음식은 `{}` 임을 확인(SC-002).

**Checkpoint**: V10 백필로 JSON 칼럼이 원본 번역과 동일 집합을 담는다(레거시 테이블은 아직 존재 — 다음 단계에서 제거).

---

## Phase 5: User Story 3 - 레거시 번역 테이블 제거로 단일 출처화 (Priority: P3)

**Goal**: 백필 검증 후 두 번역 테이블과 관련 영속 코드를 제거해 음식 번역의 단일 출처를 `food` 행 JSON 으로 확정한다.

**Independent Test**: 스키마에 `food_name_translation`·`food_description_translation` 이 없고(SC-003), 상세조회가 번역 테이블 SELECT 없이 동작(SC-004)함을 확인.

- [X] T014 [US3] `app/api/src/main/resources/db/migration/V10__jsonify_food_translations.sql` 에 이어 붙임: `DROP TABLE food_name_translation;` `DROP TABLE food_description_translation;`(두 테이블은 `food(id)` 참조 자식 — inbound FK 없어 DROP 순서 이슈 없음).
- [X] T015 [P] [US3] 레거시 영속 파일 4종 삭제: `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodNameTranslationJpaEntity.kt`, `FoodNameTranslationJpaRepository.kt`, `FoodDescriptionTranslationJpaEntity.kt`, `FoodDescriptionTranslationJpaRepository.kt`. (US1 이후 어떤 코드도 참조하지 않음 — 삭제 후 잔존 import 없어야 함. 엔티티가 남으면 DROP 된 테이블에 매핑돼 부팅 검증 실패하므로 V10 DROP 과 같은 릴리스에서 삭제.)
- [X] T016 [US3] 잔존 참조 정리: `grep -r "FoodNameTranslation\|FoodDescriptionTranslation\|findFoodNameTranslation\|findFoodDescriptionTranslation" --include=*.kt` 결과 0 확인(main·test 전부). 남은 테스트(있다면) 삭제/이관.
- [X] T017 [US3] 로컬 docker MySQL 통합 검증(quickstart §2~4): DB DROP+CREATE → 앱 부팅(V1→V10 전체) → `SHOW TABLES LIKE 'food_%_translation'` 0행(SC-003), 상세조회 curl 4종(번역 존재/부재/미지정/미지원)이 계약대로(SC-001), 비-ko 요청 SQL 로그에 번역 테이블 SELECT 없음(SC-004).

**Checkpoint**: 스키마·코드에서 레거시 번역 저장소 완전 제거, 단일 출처(JSON) 확정.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T018 [P] `./gradlew build` 전체(ArchUnit `ModuleBoundaryTest` 포함) 그린 — 모듈 경계·도메인 ORM-free·계층 의존 방향 회귀 확인.
- [X] T019 [P] 문서 동기화: `docs/architecture/food-detail-database-design.md`(있으면) 의 번역 저장 서술을 별도 테이블 → `food` JSON 칼럼으로 갱신. 필요 시 ADR 보강(저장 형태 결정 — research.md D1~D4 근거).
- [X] T020 quickstart.md Definition of Done 체크리스트 전 항목 충족 확인.

---

## Dependencies & Execution Order

- **Setup(T001)** → **Foundational(T002–T004)** 는 모든 것에 선행.
- **US1(T005–T011)**: T005·T006(테스트, [P]) → T007 → T008 → T009 → T010 → T011. Foundational 완료 후 시작, 완료 시 전 모듈 컴파일·계약 동결.
- **US2(T012–T013)**: US1 완료 후(코드가 JSON 을 읽는 상태) 마이그레이션 작성·백필 검증.
- **US3(T014–T017)**: US2 백필 검증 후. T014(DROP 추가)·T015(파일 삭제, [P])·T016(참조 정리) → T017(통합 검증). T014·T015 는 같은 릴리스에서 함께.
- **Polish(T018–T020)**: 전 스토리 후.

## Parallel Opportunities

- Foundational: **T002 [P]** 와 **T004 [P]** 병렬(서로 다른 파일; T003 은 T002 의 Green).
- US1 테스트: **T005 [P]** · **T006 [P]** 병렬(서로 다른 모듈 테스트).
- US3: **T015 [P]**(파일 삭제)는 T014(마이그레이션 편집)와 병렬 가능.
- Polish: **T018 [P]** · **T019 [P]** 병렬.

## Implementation Strategy

- **MVP = US1(T001–T011)**: 저장 형태를 JSON 으로 교체하고 상세조회 응답 계약을 동결한 채 전 모듈 테스트 그린. 이 시점에서 앱은 H2 기준 완전 동작(신규 데이터는 JSON 저장). 운영 이행(US2)·레거시 제거(US3)는 후속 증분.
- **US2**: 운영 데이터 무손실 백필(로컬 MySQL 검증). MVP 코드가 이미 JSON 을 소비하므로 안전.
- **US3**: 백필 검증 후 파괴적 DROP + 레거시 코드 제거로 단일 출처 확정.
- 각 단계는 독립 검증 가능하며 우선순위(P1→P2→P3)와 실행 순서가 일치한다.
