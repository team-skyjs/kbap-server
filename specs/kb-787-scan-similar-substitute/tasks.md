---

description: "Task list — 메뉴판 스캔 신규 메뉴의 유사 음식 대체 응답 (KB-787)"
---

# Tasks: 메뉴판 스캔 신규 메뉴의 유사 음식 대체 응답

**Input**: Design documents from `/specs/kb-787-scan-similar-substitute/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/scan-v3-substitution.md, quickstart.md

**Tests**: 작성하지 않는다(헌법 원칙 I — 요청·스펙 명시 없음). 완료 조건은 `./gradlew build` 그린(기존 `ScanControllerTest`·`OpenApiSnapshotTest` 통과) + quickstart 수동 실측.

**Organization**: 사용자 스토리별 묶음. US4(임계값 설정)는 스펙에서 범위 제외(상수 0.6)라 phase 가 없다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·선행 의존 없음 → 병렬 가능
- **[Story]**: US1(대체 판정)·US2(대체 식별 필드·3.0)·US3(대체 불가 시 현재 동작 유지)

## Path Conventions

모든 변경은 `:api` 모듈. 소스 루트 `api/src/main/kotlin/com/kbap/api/`. `:common`·`:batch`·yml·Flyway 변경 없음.

---

## Phase 1: Setup

없음 — 신규 모듈·의존·설정 파일이 없다. Phase 2 로 바로 간다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 의존하는 부품 2개 — 유사 음식 조회 서비스와 결과 타입의 새 필드.

- [X] T001 `SimilarFoodService` 복원 — `git show 15fb8e3f^:api/src/main/kotlin/com/kbap/api/scan/SimilarFoodResolver.kt` 내용을 `api/src/main/kotlin/com/kbap/api/scan/SimilarFoodService.kt` 로 생성하고 다음을 적용: (1) 클래스명 `SimilarFoodService`, `@Component` → `@Service`; (2) 생성자의 `@Value("\${kbap.vector.similarity-threshold:0.0}") similarityThreshold` 파라미터 삭제, `companion object { private const val SIMILARITY_THRESHOLD = 0.6 }` 추가, 비교식은 `it.score >= SIMILARITY_THRESHOLD`; (3) 이름당 `searcher.search(embedding, limit = 1)` 순차 `mapNotNull` 루프를 `Executors.newVirtualThreadPerTaskExecutor().use { executor -> names.zip(embeddings).map { (name, embedding) -> executor.submit<Pair<String, Long>?> { … } }.mapNotNull { it.get() }.toMap() }` 로 교체(제출 전부 → 수집, 한 람다에 `submit().get()` 금지); (4) nullable seam 주입(`TextEmbeddingClient?`·`FoodVectorSearcher?`)·blank/distinct 가드·`foodService.getReadyFoodsByIds` 재조회·전체 `try/catch(Exception)` → `log.warn("유사 음식 검색 실패 — 해당 항목들은 유사 대체 없이 응답합니다", e)` + `emptyMap()` 은 그대로 유지. 주석 없음(Kotlin 주석 금지).
- [X] T002 [P] `ScanResult.ItemRiskResult` 에 `val substituted: Boolean` 과 `val originalScannedName: String?` 추가 — `api/src/main/kotlin/com/kbap/api/scan/ScanResult.kt` (기본값 없음 — 호출부가 명시하게 강제; 유일 생성처는 `ScanService`)

**Checkpoint**: 컴파일은 T003 까지 깨진 상태가 정상(`ScanService` 생성자 호출 미갱신). T001·T002 는 서로 다른 파일이라 병렬.

---

## Phase 3: User Story 1 — 신규 메뉴를 유사한 완성 음식으로 대체해 판정 제공 (Priority: P1) 🎯 MVP

**Goal**: 스캔 2.0+ 에서 이번 스캔으로 새로 등록된 메뉴만 유사 READY 음식으로 대체해 `matched=true`·위험도·이미지·번역명을 내리고, 이력에는 대체 음식 id 가 남는다.

**Independent Test**: 벡터 활성 환경에서 DB 에 없는 오타 메뉴("김치찌게")를 2.0 으로 스캔 → 항목 `matched=true`, `foodId`=READY "김치찌개" id, `scan_history.food_id` 도 같은 id. `food` 테이블에는 "김치찌게" FAILED 행이 그대로 존재(quickstart §3).

### Implementation for User Story 1

- [X] T003 [US1] `ScanService` 대체 분기 — `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt`: (1) 생성자에 `private val similarFoodService: SimilarFoodService` 주입; (2) `scan(...)` 에 `substituteSimilar: Boolean` 파라미터 추가(`requireDetectedMenu` 뒤); (3) `resolveFoods` 반환을 `private data class ResolvedFoods(val foods: Map<String, Food>, val registeredNames: List<String>)` 로 바꾸고 `val newlyRegistered = displayNamesByMatchKey - known.keys` → `registeredNames = newlyRegistered.values.toList()`, `foods = known + foodService.createIncomplete(newlyRegistered)`; (4) `scan` 본문에서 `val similarByName = if (substituteSimilar) similarFoodService.resolveSimilarFoods(resolved.registeredNames) else emptyMap()`; (5) 항목 루프에서 `val similar = similarByName[menu.koreanName]`, `val food = similar ?: foodsByMatchKey[KoreanMenuNameNormalizer.matchKey(menu.koreanName)]`, `ItemRiskResult(..., substituted = similar != null, originalScannedName = menu.koreanName.takeIf { similar != null })`. `matched`·`koreanName`·`name`·`imageRef`·`avoidances`·`riskLevel` 계산식과 `recordHistory` 는 손대지 않는다(이력은 `items[index].foodId` 로 자동 대체 id).
- [X] T004 [US1] `ScanFacade` 플래그 전달 — `api/src/main/kotlin/com/kbap/api/scan/ScanFacade.kt`: `scanMenuBoardImage` 의 `scanService.scan(..., requireDetectedMenu = false)` 에 `substituteSimilar = false`, `scanMenuBoardImageV2` 의 `scan(...)` 에 `substituteSimilar = true` 추가.

**Checkpoint**: `./gradlew :api:compileKotlin` 그린. 이 시점에 2.0 응답은 대체가 동작하지만 새 필드는 아직 DTO 에 없다(1.0·2.0 JSON 무변경). `ScanControllerTest` 기존 시나리오는 테스트 컨텍스트에 seam 빈이 없어 영향 없음.

---

## Phase 4: User Story 2 — 대체 사실을 앱이 식별 (Priority: P1)

**Goal**: 2.0+ 응답 DTO 에 `substituted`·`originalScannedName` 을 추가하고, `X-API-Version: 3.0` 요청에만 값을 채운다(2.0 은 null, 1.0 은 필드 없음). `3.0` 을 지원 버전으로 등록한다.

**Independent Test**: 같은 사진을 3.0 과 2.0 으로 스캔 → 3.0 은 대체 항목 `substituted=true`·`originalScannedName="김치찌게"`, 비대체 `false`·null; 2.0 은 모든 항목 null·null; 1.0 은 두 키 자체 없음. `X-API-Version: 2.5` 는 400 COMMON-002, `3.0` 은 200(quickstart §2·§6).

### Implementation for User Story 2

- [X] T005 [P] [US2] `ApiVersions` 에 `const val SCAN_SUBSTITUTION = "3.0"` 추가 — `api/src/main/kotlin/com/kbap/api/core/ApiVersions.kt` (기존 `HOME_MARKETING_POPULAR`·`isAtLeast` 그대로 — 파일 덮어쓰기 금지, 한 줄 추가)
- [X] T006 [P] [US2] `ScanV2Response.ItemRiskResponse` 에 `val substituted: Boolean?`(`@field:Schema(description = "유사 완성 음식으로 대체된 항목인지. X-API-Version 3.0 이상에서만 true/false, 2.0 은 항상 null.", nullable = true)`)과 `val originalScannedName: String?`(`@field:Schema(description = "대체 전 비전이 정제한 표준 한국어 메뉴명. 3.0 이상이고 substituted=true 일 때만 값, 그 외 null.", example = "김치찌게", nullable = true)`) 추가; `from(result, currency, krwPerUnit)` 에 네 번째 파라미터 `exposeSubstitution: Boolean` 추가하고 매핑에 `substituted = it.substituted.takeIf { exposeSubstitution }`, `originalScannedName = it.originalScannedName.takeIf { exposeSubstitution }` — `api/src/main/kotlin/com/kbap/api/scan/ScanV2Response.kt`
- [X] T007 [US2] `WebConfig.configureApiVersioning` 의 `.addSupportedVersions(ApiVersions.HOME_MARKETING_POPULAR)` 를 `.addSupportedVersions(ApiVersions.HOME_MARKETING_POPULAR, ApiVersions.SCAN_SUBSTITUTION)` 으로 — `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` (T005 의존)
- [X] T008 [US2] `ScanV2Api.scan` 시그니처에 `apiVersion: String` 파라미터 추가(swagger 애너테이션 없음 — `OpenApiConfig` 가 헤더를 자동 문서화), `@Operation.description` 의 "## v1 과의 차이" 표에 3.0 열(`substituted`·`originalScannedName` 값 노출, 2.0 은 null) 추가, "## 흐름" 3단계 "매칭에 실패한(미등록) 메뉴는 대체 없이 추출 결과 그대로…" 를 "이번 스캔에서 새로 등록된 메뉴는 메뉴명 임베딩으로 벡터 색인에서 가장 유사한 음식 1건을 찾아 유사도 0.6 이상이고 완성(READY) 상태면 그 음식으로 대체한다(`matched=true`, 3.0 이상은 `substituted=true`·`originalScannedName` 으로 식별). 대체 불가·이미 등록돼 있던 미완성 메뉴는 추출 결과 그대로(정제 한국어명·가격·riskLevel UNKNOWN) 내려간다." 로 교체 — `api/src/main/kotlin/com/kbap/api/scan/ScanV2Api.kt`
- [X] T009 [US2] `ScanV2Controller.scan` 에 `@RequestHeader(ApiHeaders.API_VERSION) apiVersion: String` 파라미터 추가(`scanTicket` 뒤, `ScanV2Api` 순서와 일치), `val exposeSubstitution = ApiVersions.isAtLeast(apiVersion, ApiVersions.SCAN_SUBSTITUTION)` 계산 후 `ScanV2Response.from(result, requestedCurrency, krwPerUnit, exposeSubstitution)` 호출, import `com.kbap.api.core.ApiHeaders`·`com.kbap.api.core.ApiVersions` 추가 — `api/src/main/kotlin/com/kbap/api/scan/ScanV2Controller.kt` (T005·T006·T008 의존)

**Checkpoint**: `./gradlew :api:compileKotlin` 그린. 3.0 요청이 400 이면 T007 누락.

---

## Phase 5: User Story 3 — 대체 불가 시 현재 동작 유지·전체 실패 전파 금지 (Priority: P2)

**Goal**: seam 비활성·스코어 미달·비READY·삭제·호출 실패·기존 미완성 음식 어느 경우에도 스캔은 200 이고 해당 항목은 현재의 준비중 응답이다.

**Independent Test**: 벡터 off(로컬 기본)에서 신규 메뉴 스캔 → 200·`matched=false`·UNKNOWN·기본 이미지, 로그에 임베딩·벡터 호출 없음(quickstart §2). 벡터 on + 잘못된 `VECTOR_BUCKET` → 200·준비중·warn 1건(quickstart §5).

### Implementation for User Story 3

별도 코드 없음 — T001 이 유지한 네 가드가 전부다. 아래는 구현 중 확인 항목.

- [X] T010 [US3] T001 결과물 검토 — `api/src/main/kotlin/com/kbap/api/scan/SimilarFoodService.kt` 에서 (a) `embeddingClient ?: return emptyMap()`·`searcher ?: return emptyMap()` 두 가드가 임베딩 호출 **앞**에 있는지, (b) `getReadyFoodsByIds` 재조회가 `try` 안에 있는지, (c) `Executors…use { }` 블록이 `try` 안에 있어 `ExecutionException` 도 catch 되는지, (d) 반환 맵에 READY 재조회에서 빠진 foodId 의 이름이 남지 않는지(`foodsById[foodId]?.let`) 확인. 어긋나면 T001 로 돌아간다.
- [X] T011 [US3] T003 결과물 검토 — `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt` 에서 `similarFoodService.resolveSimilarFoods` 인자가 `resolved.registeredNames`(이번 스캔 신규 등록)만인지, `known` 의 PENDING/FAILED 이름이 섞이지 않는지 확인(FR-002·US3-5). 1.0 경로(`substituteSimilar=false`)에서 `resolveSimilarFoods` 가 호출되지 않는지 확인.

**Checkpoint**: 세 스토리 코드 완료.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T012 `./gradlew build` 전체 그린 확인 — 특히 `ScanControllerTest`(2.0 시나리오·1.0 `similarFood` 부재)·`OpenApiSnapshotTest`("선언 버전 ⊆ 그룹" 가드 — 매핑 선언이 늘지 않아 통과해야 함, 스키마 이름 충돌 없음)·`ModuleBoundaryTest`(arch). 실패하면 원인을 고치고 재실행, 테스트를 완화하지 않는다.
- [ ] T013 quickstart.md §2(벡터 off — 3.0 false/null·2.0 null/null·1.0 필드 없음)·§6(2.5 → 400, 3.0 → 200) 로컬 실측 — `specs/kb-787-scan-similar-substitute/quickstart.md`. §3·§5(벡터 on) 는 dev 자격 증명이 있을 때만.
- [X] T014 커밋 — 한국어 Conventional Commits(`feat(scan): 스캔 신규 메뉴를 유사 완성 음식으로 대체 응답 — X-API-Version 3.0 식별 필드 (KB-787)`), 본문에 설계 근거(상수 0.6·가상 스레드 병렬·2.0 null·KB-343 복원) 기록, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` 으로 마감. draft PR 은 `open-draft-pr-to-develop` 스킬.
- [ ] T015 [P] 지식 위키 반영(머지 후) — `../kbap-agenthub/wiki/api-versioning-policy.md` 에 3.0 마커(스캔 대체 식별 필드·addSupportedVersions 등록·그룹 없음) 한 줄, `menu-scan-pipeline.md` 에 대체 단계·matchKey 와의 관계(한글 음절 동일 표기는 벡터 전에 매칭) 한 줄 — `update-agenthub` 스킬.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 2 (T001·T002)**: 선행 없음. 둘은 병렬.
- **Phase 3 (US1)**: T003 은 T001·T002 완료 후. T004 는 T003 후(시그니처).
- **Phase 4 (US2)**: T005·T006 은 Phase 2 와도 병렬 가능(T006 은 T002 의 필드명만 맞추면 됨). T007 은 T005 후. T008 은 독립. T009 는 T005·T006·T008 후.
- **Phase 5 (US3)**: T010 은 T001 후, T011 은 T003 후 — 검토 전용.
- **Phase 6**: T012 는 전부 완료 후. T013 은 T012 후. T014 는 T013 후. T015 는 머지 후.

### User Story Dependencies

- **US1**: Phase 2 만 의존. 단독으로 대체 동작(필드 없이) 완성.
- **US2**: US1 의 `ScanResult` 필드(T002)에 의존하지만 T003 없이도 컴파일·동작(값은 항상 false/null). 독립 검증 가능.
- **US3**: 코드 없음. US1 코드의 성질을 검토.

### Parallel Opportunities

```text
동시 1: T001 · T002 · T005 · T006 · T008   (서로 다른 파일, 선행 없음)
동시 2: T003 · T007                        (T003 ← T001+T002, T007 ← T005)
동시 3: T004 · T009 · T010                 (T004 ← T003, T009 ← T005+T006+T008, T010 ← T001)
이후   : T011 → T012 → T013 → T014 → (머지 후) T015
```

---

## Implementation Strategy

### MVP First (US1)

1. T001·T002 → T003·T004 → `:api:compileKotlin`.
2. 벡터 활성 환경이 있으면 2.0 으로 대체 자체를 실측(필드 없이도 `matched=true`·foodId 로 확인 가능).

### Incremental Delivery

1. US1 → 대체 동작(2.0·3.0 공통).
2. US2 → DTO 필드 + 3.0 등록 + Swagger. 여기까지가 릴리스 단위 — 앱이 3.0 헤더를 올리면 식별 필드를 받는다.
3. US3 → 검토 2건.
4. Polish → build·quickstart·커밋·PR.

### 범위 밖 (하지 않는다)

- 테스트 코드·`FakeSimilarFoodSearch` 복원(요청 시 별도)
- `kbap.vector.similarity-threshold` yml·env(상수로 고정)
- `*V3Controller`·3.0 매핑·OpenApi 3.0 그룹
- 차순위 후보 탐색·타임아웃 상한·항목별 예외 격리

---

## Notes

- Kotlin 소스 주석 금지 — 근거는 커밋 메시지·research.md 에.
- `ApiVersions.kt` 는 덮어쓰지 말고 상수 한 줄만 추가(KB-778 사고 재발 방지).
- 1.0 경로는 `ScanResponse.from` 이 새 필드를 읽지 않으므로 어떤 태스크에서도 `ScanResponse.kt`·`ScanController.kt` 를 건드리지 않는다.
- 각 Phase 체크포인트에서 커밋해도 되고(논리 단위), 최종 T014 한 번으로 묶어도 된다.
