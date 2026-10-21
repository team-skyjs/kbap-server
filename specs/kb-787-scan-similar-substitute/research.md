# Research: 메뉴판 스캔 신규 메뉴의 유사 음식 대체 응답

Technical Context 에 NEEDS CLARIFICATION 은 없다. 결정과 기각 대안.

## R1. 버전 분기 — 핸들러 하나 + `X-API-Version` 헤더 비교 (3.0 은 `addSupportedVersions` 등록)

- **Decision**: `ScanV2Controller`(`@RequestMapping(..., version = "2.0+")`) 매핑을 그대로 두고, `scan()` 이 `@RequestHeader(ApiHeaders.API_VERSION) apiVersion: String` 을 받아 `ApiVersions.isAtLeast(apiVersion, ApiVersions.SCAN_SUBSTITUTION)`(상수 `"3.0"`)로 boolean 화해 응답 조립에만 넘긴다. `3.0` 은 `WebConfig.configureApiVersioning().addSupportedVersions(...)` 에 추가한다.
- **Rationale**: 클라리피케이션 B — 2.0 과 3.0 은 **같은 스키마**에 두 필드의 값만 다르다. 이것은 KB-778(홈 1.2) 과 동일한 상황이고, 그때 사용자 결정으로 "`version` 오버로드는 별도 API 로 읽힌다 — 핸들러는 하나, 분기는 헤더" 가 확립됐다(메모리 `version-branch-single-handler-preference`). `2.0+` 매핑은 3.0 요청도 받지만 지원 버전 목록에 `3.0` 이 없으면 Spring 이 400(COMMON-002)으로 거절한다 — 매핑에 선언되지 않은 버전은 자동 감지되지 않는다.
- **Alternatives considered**: (a) `@PostMapping(version = "3.0+")` 오버로드 + `2.0` 매핑을 exact 로 — Swagger 그룹 3.0 이 자동 생성되지만 `OpenApiSnapshotTest` 의 "선언 버전 ⊆ 그룹" 가드로 `OpenApiConfig` 에 3.0 그룹 빈을 추가해야 하고, 응답 조립이 두 핸들러로 갈라져 KB-778 결정에 역행. 기각. (b) `ScanV3Controller`/`ScanV3Response` — CLAUDE.md 가 버전별 클래스를 금지. 기각. (c) `HandlerMapping.API_VERSION_ATTRIBUTE` 요청 속성 — KB-778 에서 `@RequestAttribute` 인자로 못 쓰는 것으로 확인돼 헤더 문자열로 통일. 기각.
- **함정**: `ApiVersions` 파일을 덮어쓰지 말고 상수 한 줄만 추가한다 — KB-778 구현 중 덮어써 `AuthNotificationLinkTest` 3건이 깨진 전력(메모리). `HomeController`·`AuthController` 가 같은 패턴의 선례다.

## R2. `SimilarFoodResolver` → `SimilarFoodService` 로 복원 — `git show 15fb8e3f^:…/SimilarFoodResolver.kt`, 임계값 상수 0.6

- **Decision**: KB-343 삭제 커밋(`15fb8e3f`) 직전 버전을 `api/src/main/kotlin/com/kbap/api/scan/SimilarFoodService.kt` 로 복원한다(클래스명을 서비스 접미사로 개명 — 사용자 결정 2026-10-14). 수정은 두 가지 — `@Value("\${kbap.vector.similarity-threshold:0.0}")` 생성자 파라미터를 제거하고 `companion object` 의 `private const val SIMILARITY_THRESHOLD = 0.6` 으로 대체(사용자 결정 2026-10-14 — 설정·환경변수 노출 없음, 조정은 코드 변경), 그리고 `@Component` → `@Service`. yml 은 건드리지 않는다.
- **Rationale**: 복원본이 스펙을 거의 그대로 충족한다 — nullable seam 주입(`TextEmbeddingClient?`·`FoodVectorSearcher?` → 비활성 환경은 빈 결과, FR-007 "비활성"), 이름 배치 임베딩 1회, 이름당 `search(limit = 1)` 후 `score >= threshold`(FR-003 "이상"), `FoodService.getReadyFoodsByIds` 재조회(FR-003 READY·FR-011 "id 만"·소프트삭제는 `@SQLRestriction` 으로 자연 탈락), 전체 `catch` → warn 로그 + 빈 맵(FR-008). 의존 대상 `FoodVectorSearcher`·`FoodVectorMatch`·`TextEmbeddingClient`·`getReadyFoodsByIds` 는 현재 코드에 전부 존재한다(확인 완료).
- **Alternatives considered**: (a) 복원본 그대로 `@Value` + yml 프로퍼티(Jira 본문의 `kbap.vector.similarity-threshold`) — 운영자가 배포 없이 조정 가능하지만 사용자가 "상수로 심는다" 로 결정(값 하나를 위한 설정 키·env·문서 3곳 유지 비용 > 조정 빈도). 기각. (b) `FoodVectorProperties` 필드 추가 — 그 빈은 `kbap.vector.enabled=true` 일 때만 존재해 비활성 환경에서 주입이 깨진다. 기각.
- **점수 의미**: `S3VectorsFoodVectorSearcher` 는 `score = 1 - distance`(코사인 거리) 를 돌려준다. 0.6 은 Jira 의 초기 추정값이며 조정은 상수 수정(코드 변경)으로 한다.

## R3. 대체 대상 — 이번 스캔에서 새로 등록된(`registered`) 이름만, v2+ 경로만

- **Decision**: `ScanService.scan` 에 `substituteSimilar: Boolean` 파라미터를 추가한다(`ScanFacade.scanMenuBoardImage` → false, `scanMenuBoardImageV2` → true). `resolveFoods` 가 `known + registered` 를 합치는 지점에서 `registered` 키(matchKey)에 대응하는 displayName 목록을 뽑아 `similarFoodService.resolveSimilarFoods(names)` 에 넘긴다. 기존 PENDING/FAILED(`known` 에 있으나 `isReady()==false`) 와 정상 매칭은 넘기지 않는다.
- **Rationale**: FR-002·US3-5 가 명시한 범위. KB-319 당시 구현은 "READY 가 아닌 전부"(기존 미완성 포함)를 조회했는데 이번 Jira 는 "새로 저장된 메뉴만" 으로 좁혔다 — 기존 미완성 음식은 이미 콘텐츠 파이프라인(kbap-langchain)이 채우는 중이라 대체하지 않는다.
- **Alternatives considered**: `requireDetectedMenu` 플래그를 v2 마커로 겸용 — KB-343 이 `similarFoodFallback` 과 빈 추출 400 을 분리한 이유(한 플래그가 두 의미를 겸하면 한쪽 변경이 다른 쪽을 끌고 간다)를 되돌린다. 기각. 별도 `ScanMode` enum — 불리언 두 개로 충분. 기각.
- **알아둘 점 (스펙 예시의 정확성)**: `KoreanMenuNameNormalizer.matchKey` 는 한글 음절만 남기므로 "김치 찌개" 와 "김치찌개" 는 **같은 키로 DB 조회에서 이미 매칭**된다 — 스펙 US1 의 예시는 벡터 단계까지 오지 않는다. 벡터 단계가 실제로 잡는 것은 matchKey 로 안 잡히는 표기 차이(오타 "김치찌게", 축약 "돼국"→"돼지국밥", 수식어 "얼큰김치찌개")다. 기능 범위·요구사항은 바뀌지 않는다.

## R4. 응답 노출 — `ScanResult` 는 항상 채우고, `ScanV2Response.from` 이 버전으로 가린다

- **Decision**: `ScanResult.ItemRiskResult` 에 `substituted: Boolean`·`originalScannedName: String?` 을 추가한다(서비스는 항상 사실대로 채움). `ScanV2Response.ItemRiskResponse` 에 `substituted: Boolean?`·`originalScannedName: String?` 을 추가하고 `from(result, currency, krwPerUnit, exposeSubstitution: Boolean)` 이 `exposeSubstitution=false` 면 둘을 null 로 채운다. `ScanResponse.from`(1.0) 은 새 필드를 읽지 않는다.
- **Rationale**: 클라리피케이션 B(2.0 은 키 존재·값 null, 3.0 은 값). `substituted` 가 non-null `Boolean` 이면 2.0 에서 false 가 나가 "대체 안 됨" 으로 오독되므로 nullable 로 둔다. 1.0 은 DTO 가 별개(`ScanResponse`)라 바이트 동일(SC-004).
- **Alternatives considered**: `@JsonInclude(NON_NULL)` 로 2.0 에서 키 자체를 빼기 — 사용자 결정은 "null 값으로 응답은 나간다". 기각. 서비스가 버전을 받아 결과 자체를 다르게 채우기 — 버전 해석은 web 계층 소유(KB-778·위키 정책). 기각.

## R5. 스캔 이력 — 변경 없음

- **Decision**: `recordHistory` 는 `items[index].foodId` 를 기록하므로 대체 항목은 자연히 대체 음식 id 가 들어간다(FR-010). 코드 변경 없음.
- **Rationale**: 최근 스캔·북마크·주문 흐름(`getRecentScannedFoods`·`findRecentReadyFoodIds`)이 READY 음식만 보여주므로 대체 음식 id 를 기록해야 SC-005 가 성립한다. 신규 등록된 미완성 음식 행은 그대로 남아 콘텐츠 파이프라인을 탄다(US1-2).

## R6. 실패 격리 — 스캔 단위 전체 catch (복원본 그대로)

- **Decision**: 복원본의 `try { embed → search → getReadyFoodsByIds } catch (Exception) { warn; emptyMap() }` 을 유지한다. 임베딩은 이름 전체를 한 호출로 보내므로 실패하면 그 스캔의 **모든 신규 항목**이 대체 없이 준비중으로 나간다.
- **Rationale**: FR-008 "스캔 전체 실패로 번지지 않는다 · 실패는 운영 로그" 충족. 스펙 US3-4 의 "해당 항목만 준비중" 은 대체 후보 항목 전체가 준비중이 되는 것으로 만족된다(정상 매칭 항목은 영향 없음). 항목별 격리(이름당 임베딩 호출)는 호출 수를 N 배로 늘려 지연·비용만 커진다. 기각.
- **타임아웃**: OpenAI 임베딩·S3 Vectors SDK 의 기본 타임아웃을 따른다 — 별도 상한을 두지 않는다(메모리 `no-extra-hardening-features`). 지연이 문제로 드러나면 그때 조정.
- **벡터 검색은 가상 스레드로 병렬 (사용자 결정 2026-10-14)**: 복원본의 `mapNotNull` 순차 루프를 `Executors.newVirtualThreadPerTaskExecutor().use { }` 로 펼쳐 이름당 `search` 를 동시에 보낸다 — 요청을 처리하는 스레드가 가상이든 플랫폼이든 요청 안의 루프는 명시적으로 펼쳐야 병렬이 되기 때문. 지연은 "질의 1회 × N" 에서 "가장 느린 1건" 으로 떨어진다. 임베딩(1회)·READY 재조회(1회)는 병렬 대상이 아니다. `Future.get()` 이 던지는 `ExecutionException` 은 바깥 `try/catch(Exception)` 이 그대로 잡으므로 언랩 없이도 실패 격리 의미(전부 대체 없음 + warn 로그)는 유지된다 — 로그의 cause 가 한 겹 감싸지는 것만 감수. executor 는 `use` 블록 종료 시 close 되며 남은 작업을 기다린다(가상 스레드라 생성 비용 무시). 순차 유지 안은 "신규 메뉴 0~3건이라 체감 없음" 근거로 제안됐으나 사용자가 병렬을 선택.

## R7. 테스트 — 작성하지 않는다 (헌법 원칙 I)

- **Decision**: 신규 테스트 없음. 기존 `ScanControllerTest`(2.0 시나리오는 특정 필드만 jsonPath 로 보고 `similarFood` 부재만 확인)와 `OpenApiSnapshotTest`(그룹 가드는 매핑 선언만 검사)가 그대로 통과해야 한다. 테스트 컨텍스트는 임베딩·벡터 빈이 없어 resolver 가 빈 결과를 돌려주므로 모든 2.0 항목은 `substituted=null` 로 나간다.
- **Rationale**: 2026-10-14 헌법 8.0.0 — 사용자 요청·스펙 명시 때만. 스펙의 "Independent Test" 는 검증 방법 서술이지 작성 지시가 아니다. 요청이 오면 KB-343 이 삭제한 `FakeSimilarFoodSearch`(`git show 15fb8e3f^:api/src/test/kotlin/com/kbap/api/scan/FakeSimilarFoodSearch.kt`)를 복원해 `@IntegrationTest` 의 `@Import` 목록에 넣는 것이 출발점이다 — 단, 컨텍스트 조합이 바뀌므로 합성 애너테이션 한 곳에서만 추가한다(KB-392).

## R8. 문서 — Swagger 문구만, 그룹·ADR·위키 추가 없음

- **Decision**: `ScanV2Api` 의 흐름 3단계 문구("매칭 실패 메뉴는 대체 없이…")를 "신규 등록 메뉴는 유사 완성 음식(임계값 이상·READY)으로 대체되고 3.0 이상은 `substituted`·`originalScannedName` 로 식별" 로 바꾸고 버전 표에 3.0 열을 더한다. `ScanV2Response` 두 필드의 `@Schema(description)` 에 2.0 null·3.0 값을 적는다. Swagger 그룹(1.0/1.1/2.0)은 늘지 않는다 — 3.0 계약은 2.0 그룹 문서의 필드 설명으로 읽는다(KB-778 의 1.2 와 동일).
- **위키 반영**: 구현 머지 후 `api-versioning-policy.md`(3.0 마커 = 스캔 대체 필드)·`menu-scan-pipeline.md`(대체 단계·matchKey 와의 관계)에 한 줄씩 — `update-agenthub` 스킬로 별도 수행.
