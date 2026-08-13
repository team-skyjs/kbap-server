---

description: "Task list for 스캔 v2 — 서버 OCR 파이프라인과 유사 음식 대체 응답 (KB-319)"
---

# Tasks: 스캔 v2 — 서버 OCR 파이프라인과 유사 음식 대체 응답

**Input**: Design documents from `specs/kb-319-scan-ocr-vector-fallback/`

**Prerequisites**: [plan.md](./plan.md) · [spec.md](./spec.md) · [research.md](./research.md) · [data-model.md](./data-model.md) · [contracts/scan-v2.md](./contracts/scan-v2.md) · [contracts/vector-food-document.md](./contracts/vector-food-document.md)

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 스토리는 구현 전에 실패하는 테스트를 먼저 쓰고 **Red 를 눈으로 확인**한다. 모든 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).

**Organization**: 스토리별로 묶어 각각 독립적으로 구현·검증·전달할 수 있게 한다. US1(v2 hit 경로)만으로도 배포 가능한 MVP 다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 실행 가능(다른 파일, 선행 미완료 의존 없음)
- **[Story]**: 소속 사용자 스토리(US1·US2·US3)
- 모든 태스크에 정확한 파일 경로를 적는다

## Path Conventions

모듈러 모놀리스 — `common/src/{main,test}/kotlin/com/kbap/common/...`, `api/src/{main,test}/kotlin/com/kbap/api/...`, `infra/llm/src/{main,test}/kotlin/com/kbap/infra/llm/...`. **워크트리 루트에서 실행한다.**

---

## Phase 1: Setup

**Purpose**: 변경 전 기준선 확보 — 이후 나타나는 Red 가 내 변경 때문임을 보장한다.

- [X] T001 기준선 그린 확인: `./gradlew :api:test --tests "*ScanControllerTest" :infra:llm:test` 실행해 전부 통과함을 확인한다(실패 항목이 있으면 먼저 원인을 규명하고 이 작업을 시작하지 않는다)

**Checkpoint**: 기준선 그린.

---

## Phase 2: Foundational

없음 — 스토리 간 공유 선행물이 없다(의존 추가·응답 필드는 소속 스토리 안에서 처리).

---

## Phase 3: User Story 1 — 사진 업로드만으로 스캔 완료 (P1) 🎯 MVP

**Goal**: `X-API-Version >= 2026.08.07` 헤더로 `imagePath` 만 보내면 서버가 OCR·정제해 등록 음식 hit 정보를 내려준다. 헤더 없는 기존 요청은 종전 계약 그대로.

**Independent Test**: 등록(READY) 음식이 나오는 fake 추출 결과로, 헤더 + `imagePath` 만의 스캔 요청이 200 과 등록 음식 정보(v1 동등)를 반환하면 통과.

- [X] T002 [P] [US1] `infra/llm/src/test/kotlin/com/kbap/infra/llm/menu/OpenAiMenuBoardVisionExtractorTest.kt` — 서버 OCR 프롬프트 분기 시나리오 추가: ① `ocrItems` 가 비어 있으면 조립된 user/system 프롬프트에 OCR 힌트 절("OCR:"·idx 목록)과 matchedIdx 매칭 지시가 **없고** 추출·정제 규칙(koreanName·가격 정수화)은 유지됨 ② 비어 있지 않으면 기존 프롬프트 그대로. 실행해 **Red 확인**(`./gradlew :infra:llm:test`)
- [X] T003 [US1] `infra/llm/src/main/kotlin/com/kbap/infra/llm/menu/OpenAiMenuBoardVisionExtractor.kt` — `ocrItems.isEmpty()` 이면 힌트 없는 서버 OCR 프롬프트(시스템·유저 변형)로 분기 구현. T002 를 **Green 으로 전환**
- [X] T004 [US1] `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` — v2 분기 시나리오 추가(`FakeMenuBoardVisionExtractor` 재사용, `X-API-Version` 헤더 파라미터를 스캔 헬퍼에 추가): ① `2026.08.07` 헤더 + `imagePath` 만 → 200, `results[].idx == null`, hit 항목은 등록 음식 정보(riskLevel·foodId·name — v1 스캔과 동등) ② 헤더가 있어도 `imagePath` 누락 → 400 `COMMON-002` ③ `2026.08.06`·형식 오류 헤더 + items 누락 → 400 `COMMON-002`(v1 계약) ④ v2 스캔 후 `scan_history` 기록(가격 보존)·스캔 카운트 증가 확인. 실행해 **Red 확인**
- [X] T005 [US1] v2 분기 구현으로 T004 **Green 전환**: `api/src/main/kotlin/com/kbap/api/scan/ScanRequest.kt`(items `@NotEmpty`·`@Size`min 제거 — 컨트롤러 분기 조건부 검증으로 이동, v1 경로 빈 items 는 `BusinessException(ErrorCode.INVALID_REQUEST)` 로 종전 400 `COMMON-002` 유지) + `api/src/main/kotlin/com/kbap/api/scan/ScanController.kt`(`@RequestHeader("X-API-Version", required = false)` 수신, `ApiVersion.parseOrNull >= ApiVersion(2026, 8, 7)` 판정 — KB-300 `MemberController` 선례, v2 면 `ocrItems = emptyList()` 로 서비스 호출)
- [X] T006 [P] [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanApi.kt` — swagger 갱신: `X-API-Version` 헤더 `@Parameter`(HEADER, 선택, `2026.08.07` 이상 = 서버 OCR — KB-300 `MemberApi` 문구 선례), v2 요청 예시(imagePath 만)·v1 예시 유지, `@Operation` description 에 분기 동작·idx null 명시. **Spring 애너테이션은 컨트롤러에만**(파라미터 애너테이션 위치 규약)

**Checkpoint**: v2 hit 경로 + v1 회귀 가드 그린 — 이 시점에 배포 가능한 MVP.

---

## Phase 4: User Story 2 — 미등록 음식은 유사 음식으로 대체 응답 (P2)

**Goal**: miss 항목을 임베딩→벡터 검색→MySQL 재조회로 유사 음식(`similarFood`)으로 대체 응답한다. 임계 미달·장애·빈 부재는 유사 대체 없이 스캔 성공.

**Independent Test**: fake `TextEmbeddingClient`·`SimilarFoodSearcher` 를 등록하고 miss 메뉴를 v2 스캔하면 `similarFood` 에 READY 음식의 이름·설명·`imageRef` 가 담기면 통과.

- [X] T007 [US2] `gradle/libs.versions.toml` 에 `mongodb-driver-sync` 좌표 추가 + `api/build.gradle.kts` 에 `"implementation"(libs.mongodb.driver.sync)` 추가, `./gradlew :api:compileKotlin` 으로 확인
- [X] T008 [P] [US2] ~~`SimilarFoodResolverTest.kt` 별도 단위~~ → **`ScanControllerTest` 통합 시나리오로 흡수**(구현 중 변경 — `FoodService` 가 final 협력자라 단위 페이크 비용 > 통합 커버 비용): 임계 미달·searcher 예외·READY 아님/미존재 foodId 제외를 fake `FakeSimilarFoodSearch`(임베딩+검색 겸용) + Testcontainers 로 검증. "빈 부재 no-op" 은 nullable 주입 가드 1줄이라 별도 테스트 생략
- [X] T009 [US2] `api/src/main/kotlin/com/kbap/api/scan/SimilarFoodSearcher.kt` 신규(fun interface + `SimilarFoodDocument(foodId, score)` — 테스트 대역용, data-model 참조) + `api/src/main/kotlin/com/kbap/api/scan/SimilarFoodResolver.kt` 신규(옵셔널 의존 주입 `ObjectProvider`/nullable, 임베딩→검색→임계→`FoodService` 재조회, 예외는 warn 로그 후 빈 결과). T008 을 **Green 으로 전환**
- [X] T010 [US2] `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` — miss 유사 폴백 통합 시나리오 추가(fake `SimilarFoodSearcher`·`TextEmbeddingClient` 테스트 빈 등록): ① miss 항목에 `similarFood`(foodId·name=`displayName(lang)`·koreanName·description·imageRef 공개 URL) + `matched=false`·원 항목 riskLevel UNKNOWN·조사 대기 foodId 는 별개 유지 ② `similarFood.foodId` 로 `GET /api/v1/foods/detail` 정상 조회 ③ fake 가 임계 미달 score 반환 → `similarFood: null` ④ v1 경로(헤더 없음) 응답의 `similarFood` 는 항상 null. 실행해 **Red 확인**
- [X] T011 [US2] `api/src/main/kotlin/com/kbap/api/scan/ScanResult.kt`·`ScanResponse.kt`(SimilarFood 타입 — 필드명 `foodId`·`name`·`koreanName`·`description`·`imageRef`, FoodDetailResponse 규약 준수) + `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt`(v2 경로에서만 miss 항목을 `SimilarFoodResolver` 로 보강 — 외부 호출은 트랜잭션 밖 유지). T010 을 **Green 으로 전환**
- [X] T012 [US2] `api/src/main/kotlin/com/kbap/api/scan/DocumentDbSimilarFoodSearcher.kt` 신규 — MongoClient 조립(`@Configuration` + `@ConditionalOnProperty("kbap.vector.enabled")` + `@ConfigurationProperties("kbap.vector")`)과 `$search` vectorSearch 집계([contracts/vector-food-document.md](./contracts/vector-food-document.md) 파이프라인 — cosine·k·`$meta: searchScore` projection). 쿼리 문서 조립은 단위 테스트 가능한 범위만 검증, 실검증은 T017(dev 수동)
- [X] T013 [P] [US2] `api/src/main/resources/application.yml`(+`application-{local,dev,prod}.yml`) — `kbap.vector.{enabled,uri,database,collection,similarity-threshold}` 추가(local `enabled: false`), dev/prod 프로필에 `kbap.llm.embedding.enabled: true`(data-model 설정 절 참조. 실 uri/시크릿은 환경 변수·SSM 관례를 따른다)

**Checkpoint**: miss 유사 폴백 그린(페이크 기준) + 폴백 안전성 검증 완료.

---

## Phase 5: User Story 3 — 기존 스캔 플로우 무영향 (P3)

**Goal**: 기존 v1 계약(요청 형식·검증·응답·오류 코드·부수 동작)이 문자 그대로 유지된다.

**Independent Test**: 기존 `ScanControllerTest` 시나리오가 **무수정**(헬퍼 시그니처 확장 제외)으로 전량 통과하면 통과.

- [X] T014 [US3] v1 회귀 확정: 기존 `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 의 v1 시나리오가 검증 완화(T005) 후에도 전부 통과하는지 실행 확인 + 누락 가드 보강(items 누락·빈 배열 → 400 `COMMON-002`, `MENU_BOARD_RECOGNITION_FAILED` 503, 빈 추출 정상 200 이 v1 경로에서 종전과 동일)

**Checkpoint**: 회귀 0건 확정.

---

## Phase 6: Polish & Cross-Cutting

- [X] T015 [P] ArchUnit·전체 빌드 그린: `./gradlew build` (`ModuleBoundaryTest` — 컨트롤러 `/api/v` 규약·common.port 순수성 포함 전 모듈)
- [X] T016 [P] 주석 규약 점검: 신규·수정 Kotlin 파일에 KDoc·서사형 주석 없는지, 코드로 드러나지 않는 제약(테스트 대역용 인터페이스 사유·의도적 무트랜잭션·부분 성공 폴백)만 짧은 라인 주석인지 확인
- [ ] T017 dev 실검증([quickstart.md](./quickstart.md) 수동 절차): dev 프로필로 `$search` 벡터 문법·DocumentDB TLS 연결·`similarFood` 실응답 확인 — 로컬 재현 불가 항목의 유일한 검증 지점. Swagger UI 에서 헤더 파라미터·v2 안내 육안 확인 포함
- [X] T018 [P] 계약 문서 정합: 구현 결과가 [contracts/scan-v2.md](./contracts/scan-v2.md)·[contracts/vector-food-document.md](./contracts/vector-food-document.md) 와 어긋난 부분이 생겼으면 문서를 갱신(특히 `$search` 실문법·threshold 초기값) — items nullable 완화·`$search` 파이프라인 구현 일치 확인. T017(dev 실검증)에서 실문법 어긋나면 재갱신

---

## Dependencies

```
T001 (기준선)
  └─→ US1: T002 → T003 (infra:llm 프롬프트)     # T002∥T004 병렬 가능
       T004 → T005 → T006                        # T006 은 T005 후 병렬
  └─→ US2: T007 → T008 → T009 → T010 → T011 → T012 → T013   # US1(T005) 완료 후 시작 — v2 분기 전제
                                                  # T008∥T007, T013∥T012 병렬 가능
  └─→ US3: T014                                   # T005 이후 언제든
Polish: T015~T018 — 전 스토리 후. T015∥T016∥T018 병렬
```

- **US1 → US2 순서 필수**: 유사 폴백은 v2 분기(T005) 위에서만 동작한다.
- US3 는 T005 직후부터 독립 실행 가능.

## Parallel Execution Examples

- US1: T002(infra:llm 테스트)와 T004(api 테스트)는 다른 모듈 — 동시 작성 가능. T006(swagger)은 T005 와 파일 분리돼 병렬.
- US2: T007(빌드 의존)과 T008(테스트 작성) 병렬. T013(yml)은 T012 와 병렬.
- Polish: T015·T016·T018 동시 진행.

## Implementation Strategy

**MVP = Phase 1 + US1**: v2 서버 OCR + hit 경로만으로 신버전 앱이 동작한다(miss 는 기존 미등록 응답). 여기서 배포 가능.

**증분 전달**: US2(유사 폴백)는 fake 로 그린 확정 후 T017 dev 실검증으로 마무리 — 벡터 데이터 적재 전이면 `similarFood` 는 실환경에서 항상 null(계약상 정상). US3 는 각 단계의 회귀 안전망.
