# Implementation Plan: 메뉴판 스캔 신규 메뉴의 유사 음식 대체 응답

**Branch**: `feat/kb787-scan-similar-substitute` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-787-scan-similar-substitute/spec.md`

## Summary

스캔 2.0+ 에서 이번 스캔으로 **새로 등록된** 메뉴만 메뉴명을 임베딩해 벡터 검색(limit 1)하고, 스코어가 임계값(상수 0.6) 이상이며 READY 인 음식이면 항목을 그 음식으로 대체해 `matched=true` 로 응답한다. 응답 항목에 `substituted`·`originalScannedName` 을 추가하되 값은 **3.0 요청에만** 채우고 2.0 은 null, 1.0 은 무변경이다. 기술적으로는 KB-343 에서 삭제된 `SimilarFoodResolver` 를 git 이력에서 `SimilarFoodService` 로 복원하고, 버전 분기는 KB-778 선례(핸들러 하나 + `@RequestHeader` + `ApiVersions.isAtLeast`)를 따른다 — 새 매핑·새 컨트롤러·새 Swagger 그룹 없이 `3.0` 을 `addSupportedVersions` 에 등록한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택)

**Primary Dependencies**: 변경 없음 — 기존 seam `TextEmbeddingClient`(`common.port.llm`)·`FoodVectorSearcher`(`common.domain.food.vector`)와 조립 config(`LlmConfiguration`·`FoodVectorConfig`)를 그대로 소비. 신규 의존 0.

**Storage**: 변경 없음 — DB 스키마·Flyway 무변경. `scan_history.food_id` 에 대체 음식 id 가 들어가는 것은 기존 컬럼의 값 변화.

**Testing**: 헌법 원칙 I(Tests on Request) — 신규 테스트는 작성하지 않는다. 기존 `ScanControllerTest`·`OpenApiSnapshotTest` 가 통과해야 한다(`./gradlew build`).

**Target Platform**: `:api` 스캔 기능 패키지(`com.kbap.api.scan`) + `api.core`(`ApiVersions`·`WebConfig`). yml 변경 없음

**Project Type**: web-service — 기존 응답 계약 확장(additive) + 버전 마커 추가

**Performance Goals**: 신규 메뉴가 0건인 스캔은 임베딩·벡터 호출 0건. 신규 메뉴 N건은 임베딩 1회(배치) + 벡터 검색 N회(limit 1, **가상 스레드 병렬** — 지연은 가장 느린 1건) + READY 재조회 1회

**Constraints**: 스캔 1.0 바이트 동일 · 2.0 스키마는 두 필드 추가(null) 외 동일 · 대체 단계 실패는 스캔 실패로 전파 금지 · 벡터/임베딩 비활성 환경(로컬·테스트) 정상 부팅·정상 응답

**Scale/Scope**: 수정 8파일(`ScanService`·`ScanFacade`·`ScanResult`·`ScanV2Response`·`ScanV2Controller`·`ScanV2Api`·`ApiVersions`·`WebConfig`) + 복원 1파일(`SimilarFoodService` — 구 `SimilarFoodResolver`) — 소형

## Constitution Check

*GATE: 통과(위반 없음). Phase 1 설계 후 재평가 — 동일.*

- **I. Tests on Request**: 사용자 요청·스펙 명시 없음 → 테스트 미작성. 기존 스위트 그린 유지가 조건. 통과.
- **II. Bounded Contexts**: 변경은 `com.kbap.api.scan`·`api.core` 안. `common.domain.food.vector` seam 소비는 KB-319 당시와 동일 방향(api→common). 통과.
- **III. Layered Dependency Direction**: 임베딩·벡터 검색은 seam 인터페이스로만 소비(`TextEmbeddingClient?`·`FoodVectorSearcher?`), 구현 직접 참조 없음. 버전 해석은 web 계층(컨트롤러)이 소유하고 서비스는 boolean 만 받는다. 통과.
- **IV. Persistence Ownership**: 영속 무변경. READY 재조회는 기존 `FoodService.getReadyFoodsByIds`(명시 `@Transactional(readOnly)`). 외부 호출(임베딩·벡터)은 트랜잭션 밖 — `ScanService.scan` 은 트랜잭션 없음. 통과.
- **V. Domain Content Language Policy**: 대체 항목의 `name` 은 `food.displayName(lang)`(기존 매칭 경로와 동일 ko 폴백), `koreanName` 은 `displayName(KO)`. 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-787-scan-similar-substitute/
├── plan.md              # This file
├── research.md          # Phase 0 — 결정 8건(버전 분기·복원·대상 범위·응답 노출·이력·실패 처리·테스트·문서)
├── data-model.md        # Phase 1 — ScanResult/ScanV2Response 필드 추가·대체 판정 상태(DB 변경 없음)
├── quickstart.md        # Phase 1 — 로컬 실측 절차(벡터 on/off)
├── contracts/
│   └── scan-v3-substitution.md  # 3.0/2.0/1.0 응답 계약 + 프로퍼티
└── tasks.md             # /speckit-tasks 산출(이 커맨드 아님)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── core/
│   ├── ApiVersions.kt                 # SCAN_SUBSTITUTION = "3.0" 상수 추가(기존 HOME_MARKETING_POPULAR 유지 — 덮어쓰기 금지)
│   └── config/WebConfig.kt            # addSupportedVersions(..., ApiVersions.SCAN_SUBSTITUTION)
└── scan/
    ├── SimilarFoodService.kt          # 복원: git show 15fb8e3f^:api/src/main/kotlin/com/kbap/api/scan/SimilarFoodResolver.kt 를 SimilarFoodService 로 개명 — 임계값 상수 0.6
    ├── ScanService.kt                 # substituteSimilar 파라미터·registered 대상 유사 조회·대체 분기·substituted/originalScannedName 채움
    ├── ScanFacade.kt                  # scanMenuBoardImage → substituteSimilar=false, scanMenuBoardImageV2 → true
    ├── ScanResult.kt                  # ItemRiskResult 에 substituted: Boolean, originalScannedName: String? 추가
    ├── ScanV2Response.kt              # ItemRiskResponse 에 substituted: Boolean?, originalScannedName: String? 추가, from(..., exposeSubstitution)
    ├── ScanV2Controller.kt            # @RequestHeader(X-API-Version) → ApiVersions.isAtLeast(_, SCAN_SUBSTITUTION) → from(...)
    └── ScanV2Api.kt                   # 흐름 3단계 문구·버전 표(3.0 열) 갱신
```

**Structure Decision**: 기존 기능 패키지 안에서 끝낸다. 신규 파일은 복원 1개뿐이고, `*V3Controller`·`*V3Response`·3.0 매핑·3.0 Swagger 그룹은 만들지 않는다(CLAUDE.md 버저닝 규약 + KB-778 선례). `:common`·`:batch`·조립 config(`FoodVectorConfig`·`LlmConfiguration`)는 한 줄도 건드리지 않는다.

## 구현 노트 (Phase 1 설계 확정)

- **버전 분기는 web 계층 한 곳**: `ScanV2Controller.scan` 이 `@RequestHeader(ApiHeaders.API_VERSION) apiVersion: String` 을 받아 `ApiVersions.isAtLeast(apiVersion, ApiVersions.SCAN_SUBSTITUTION)` 을 `ScanV2Response.from(result, currency, krwPerUnit, exposeSubstitution)` 에 넘긴다. 서비스는 버전을 모른다 — 대체는 2.0·3.0 공통이므로 `ScanService` 는 `substituteSimilar: Boolean`(v1 false / v2+ true)만 받는다.
- **`3.0` 등록을 빼먹으면 400(COMMON-002)**: 매핑에 `3.0` 선언이 없으므로 Spring 이 자동 감지하지 못한다. `WebConfig.configureApiVersioning` 의 `addSupportedVersions` 에 상수를 추가한다(KB-778 의 `1.2` 와 동일 패턴). `OpenApiSnapshotTest` 의 "선언 버전 ⊆ 그룹" 가드는 매핑 선언만 보므로 그룹 추가 불필요.
- **대체 대상은 `resolveFoods` 의 `registered` 키만**: `resolveFoods` 가 `known + registered` 를 합쳐 돌려주므로, 대체 후보 이름은 `registered` 쪽 matchKey 에 대응하는 displayName(`displayNamesByMatchKey`)으로 뽑는다. 기존 PENDING/FAILED(`known` 이지만 `isReady()==false`)는 벡터 조회 대상이 아니다(FR-002·US3-5).
- **항목 조립**: `food = foodsByMatchKey[key]`; `similar = substituteSimilar ? similarByName[menu.koreanName] : null`(registered 만 조회했으므로 known 은 자연히 null); `similar != null` 이면 `food = similar, matched = true, substituted = true, originalScannedName = menu.koreanName`, 아니면 기존 로직 + `substituted = false, originalScannedName = null`. `koreanName`·`name`·`imageRef`·`avoidances`·`riskLevel` 은 대체된 `food` 로 기존 식 그대로 계산된다.
- **이력은 손대지 않는다**: `recordHistory` 가 `items[index].foodId` 를 쓰므로 대체 항목은 자동으로 대체 음식 id 가 기록된다(FR-010).
- **복원 파일 수정 범위**: (1) 클래스명 `SimilarFoodService`(`@Service`), (2) 생성자의 `@Value(...) similarityThreshold` 파라미터를 제거하고 `companion object { private const val SIMILARITY_THRESHOLD = 0.6 }` 상수로 대체, (3) 이름당 `search` 를 `Executors.newVirtualThreadPerTaskExecutor().use { executor -> submit … get }` 로 병렬화(순차 `mapNotNull` 교체). 나머지(nullable seam 주입·names 배치 임베딩·limit 1·`getReadyFoodsByIds` 재조회·전체 catch → warn 로그 + emptyMap)는 그대로 — 스펙 FR-003·007·008·011 을 이미 충족한다. yml·`FoodVectorProperties` 는 건드리지 않는다.
- **1.0 무변경 보장**: `ScanResponse.from` 은 새 필드를 읽지 않는다. `ScanResult` 에 필드가 늘어도 1.0 JSON 은 바뀌지 않는다(SC-004).
- **2.0 null 보장**: `ScanV2Response.ItemRiskResponse.substituted` 는 `Boolean?` 다. `exposeSubstitution=false` 면 두 필드를 null 로 채운다 — Jackson 기본 설정이 null 을 직렬화하므로 키는 존재하고 값은 null(FR-005a).
- **Swagger**: `ScanV2Api` 설명의 흐름 3단계("매칭 실패 메뉴는 대체 없이…")를 현행화하고, 버전 표에 3.0 열(두 필드 값 노출)을 추가한다. `ScanV2Response` 필드 `@Schema` 에 "3.0 이상에서만 값, 2.0 은 null" 을 적는다(FR-012). `OpenApiConfig.NOTICE` 의 그룹 안내 문구는 그룹이 늘지 않으므로 그대로.

## Complexity Tracking

> 위반 없음 — 해당 없음.
