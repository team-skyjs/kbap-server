# Tasks: 온보딩 재료 81종 이름·이미지 공개 조회

**Input**: Design documents from `/specs/kb-326-ingredient-images/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ingredients-api.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 원칙 I) — 각 스토리는 실패 테스트(Red) 작성·확인 후 구현(Green)한다.

**Organization**: 스토리별 페이즈. 모든 경로는 워크트리 루트(`.claude/worktrees/kb-326-ingredient-images/`) 기준.

## Phase 1: Setup

없음 — 기존 모듈·패키지 구조를 그대로 사용한다(신규 의존성·설정 없음).

## Phase 2: Foundational (두 스토리 공통 전제 — 엔티티·스키마)

`imagePath` 필드와 `image_path` 컬럼은 US1(응답 노출)·US2(시드 적재)가 함께 쓰는 전제다. 마이그레이션은 컬럼 추가+시드를 한 파일에 담으므로(순서 독립 원칙) 여기서 만든다.

- [x] T001 `common/src/main/kotlin/com/kbap/common/domain/ingredient/model/Ingredient.kt` 에 `imagePath: String? = null` 필드 추가 — `@Column(name = "image_path", length = 255)`
- [x] T002 Flyway 마이그레이션 생성 `api/src/main/resources/db/migration/V<생성시각 timestamp>__ingredient_image_path.sql` — `ALTER TABLE ingredients ADD COLUMN image_path VARCHAR(255) NULL;` + `UPDATE ingredients SET image_path = CONCAT('images/webp/', LOWER(code), '.webp');` (파일명은 생성 시점 로컬 시각, 정수 버전 금지)

**Checkpoint**: 기존 통합 테스트 스위트가 엔티티↔스키마 정합(`ddl-auto=validate`)을 검증 — `./gradlew :api:test --tests "com.kbap.api.ingredient.*"` 이 아직 없어도 임의 `@SpringBootTest` 하나로 컨텍스트 기동 확인 가능.

> **TDD 순서 주의**: 엄격한 Red 확인을 위해 **T005(US2 Red 테스트)를 T001·T002 보다 먼저** 작성해도 된다 — imagePath 부재로 컴파일 실패(Red)가 확인된다. 오케스트레이터는 T005(Red) → T001·T002(Green) → T003(Red) → T004(Green) 순서를 권장 실행 순서로 삼는다.

## Phase 3: User Story 1 — 온보딩에서 재료 목록을 사진과 함께 본다 (P1) 🎯 MVP

**Goal**: 인증 없이 `GET /api/ingredients?lang=` 로 81종 전체를 code·언어별 이름·완성 이미지 URL 과 함께 반환.

**Independent Test**: 인증 정보 없이 MockMvc 로 호출해 81건 + 필드 3종 반환 확인(컬럼만 있으면 시드와 무관하게 검증 가능 — imageUrl 은 nullable).

- [x] T003 [US1] **(Red)** `api/src/test/kotlin/com/kbap/api/ingredient/IngredientControllerTest.kt` 작성 — Kotest BehaviorSpec + `@SpringBootTest` + MockMvc + `MySqlContainerConfig`, given/when/then 한국어. 시나리오: ① Authorization 헤더 없이 `?lang=ko` → 200 + `payload.ingredients` 81건 + 각 항목 code/name/imageUrl 필드 ② `lang` 누락 → 400 ③ `?lang=en` → name 영어(예: EGG→"Egg") ④ 미지원 `?lang=fr` → 영어 폴백 ⑤ 무효 토큰 `Authorization: Bearer garbage` 동반 → 200 동일 응답. 작성 직후 실행해 **실패(Red) 확인**
- [x] T004 [US1] **(Green)** `api/src/main/kotlin/com/kbap/api/ingredient/` 에 구현 — `IngredientListResponse.kt`(`ingredients: List<IngredientItemResponse{code,name,imageUrl}>`), `IngredientQueryService.kt`(`@Transactional(readOnly = true)` `getIngredients(lang): IngredientListResponse` — `IngredientJpaRepository.findAll(Sort.by("id"))` + `displayName(lang)` + `ImageUrls.resolve(publicBaseUrl, imagePath)`, `@Value("\${kbap.storage.public-base-url:}")`), `IngredientApi.kt`(swagger 문서 — `@Tag`·`@Operation`, 인증 불필요 명시), `IngredientController.kt`(`@RequestMapping(ApiPaths.API + "/ingredients")`, `@GetMapping` + 필수 `lang` 검증 → `LanguageCode.from`, `ResponseEntity<BaseResponse<IngredientListResponse>>`). **`WebConfig` 는 수정 금지**(공개가 의도). T003 전 시나리오 통과 확인
- [x] T005 [US1] OpenAPI 스냅샷 갱신 — `api/src/test/kotlin/com/kbap/api/openapi/OpenApiSnapshotTest.kt` 의 갱신 방식(스냅샷 파일 재생성 절차)을 확인하고 새 엔드포인트 반영, 테스트 통과 확인

**Checkpoint**: `./gradlew :api:test --tests "com.kbap.api.ingredient.IngredientControllerTest" --tests "com.kbap.api.openapi.OpenApiSnapshotTest"` 전부 통과 — US1 단독 데모 가능(MVP).

## Phase 4: User Story 2 — 재료마다 이미지가 매칭되어 있다 (P2)

**Goal**: 재료 81종 전체에 `image_path` 가 규칙(`images/webp/<code소문자>.webp`)대로 적재됐음을 전수 검증으로 고정.

**Independent Test**: 리포지토리 전수 조회로 imagePath non-null + 패턴 일치 0건 이탈 확인.

- [x] T006 [US2] **(Red→Green)** `api/src/test/kotlin/com/kbap/api/ingredient/IngredientImageSeedTest.kt` 작성 — BehaviorSpec + `@SpringBootTest` + `MySqlContainerConfig`: ① 전 재료(81건) `imagePath` non-null ② 각 `imagePath == "images/webp/" + code.name.lowercase() + ".webp"` ③ 건수 = `IngredientCode.entries.size`. (T002 이전에 작성하면 Red 확인 가능 — Foundational 의 TDD 순서 주의 참조. T002 이후라면 회귀 고정 테스트로 통과 확인)

**Checkpoint**: 전수 매칭 검증 통과 — SC-002(누락 0건) 달성.

## Phase 5: Polish & Cross-Cutting

- [x] T007 전체 빌드·전 모듈 테스트 `./gradlew build` — ArchUnit(`ModuleBoundaryTest`)·기존 스위트 포함 전부 통과 확인(교차 실패는 전체 빌드로만 잡힘 — quickstart 함정 참조)
- [ ] T008 (선택) 로컬 수동 확인 — quickstart.md 의 curl 2종(토큰 없이 200/81건, lang 누락 400)

## Dependencies

```text
Foundational(T001·T002) ──▶ US1(T003→T004→T005) ──▶ Polish(T007·T008)
                       └──▶ US2(T006) ────────────┘
```

- US1·US2 는 Foundational 이후 서로 독립 — T003~T005 와 T006 은 병렬 가능.
- 권장 TDD 실행 순서(오케스트레이터): **T006(Red) → T001·T002(Green) → T003(Red) → T004(Green) → T005 → T007**.

## Parallel Example

- T006 [US2] 은 T003~T005 [US1] 과 파일이 겹치지 않아 병렬 작성 가능.
- T001(common)·T002(api 리소스)는 서로 다른 파일이나, 함께 배포돼야 validate 를 통과하므로 같은 태스크 묶음으로 처리.

## Implementation Strategy

- **MVP = Phase 2 + Phase 3(US1)**: 컬럼·시드가 마이그레이션 한 파일로 같이 들어오므로 실질적으로 US2 데이터도 MVP 시점에 적재된다. US2 페이즈는 그 적재를 전수 검증으로 고정하는 안전망.
- 스토리 단위 커밋: Foundational+US2(데이터) → US1(API) 또는 전체 1 PR — 규모가 작아(파일 7개) 단일 PR 권장, base=develop draft PR(`open-draft-pr-to-develop` 스킬).
