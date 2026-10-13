# Research: 홈 인기 음식 마케팅 목록

Technical Context 에 NEEDS CLARIFICATION 은 없다. 결정과 기각 대안, 그리고 2026-10-13 하루 안의 개정 이력.

## R1. 버전 분기 — 핸들러 하나 + 요청 버전 속성

- **Decision**: 기존 `GET /api/home` 매핑 하나를 유지하고, 컨트롤러가 `@RequestHeader(ApiHeaders.API_VERSION) apiVersion: String` 으로 받은 헤더 문자열(`SemanticApiVersionParser.Version`)을 `ApiVersions.isAtLeast(_, "1.2")` 로 boolean 화해 서비스에 넘긴다. `1.2` 은 `WebConfig.addSupportedVersions` 로 등록한다(매핑 선언이 없으면 미지원 400).
- **Rationale**: 사용자 결정 — `version = "2.1+"` 오버로드(1차 구현)는 "별도 API" 로 읽혀 기각됐고, 이후 "1.2 요청이면 무조건 풀" 로 요구가 확정돼 분기는 필요하되 핸들러는 하나여야 했다. 버전 해석은 web 계층이 소유하고 서비스·도메인은 헤더를 모른다(위키 정책 유지).
- **Alternatives considered**: `version = "2.1+"` 오버로드 — Swagger 가 매핑에서 버전·그룹을 자동 생성해 문서가 정확하지만 기각. 버전 분기 없이 전 버전 대체(2차) — 구 앱까지 바뀌어 기각. `X-App-Version` 분기 — 위키에서 이미 기각된 안.
- **선례·함정**: `com.kbap.api.core.ApiVersions.isAtLeast(String, String)` 은 **이미 있던 부품**이다 — `AuthController` 가 로그인·로그아웃의 기기 연결(1.1+)을 같은 방식(`@RequestHeader` 문자열 비교)으로 분기한다. 구현 중 이 파일을 모르고 덮어써 `AuthNotificationLinkTest` 3건이 깨졌다가 복원했다(상수 `HOME_MARKETING_POPULAR` 만 추가). 요청 속성 `HandlerMapping.API_VERSION_ATTRIBUTE` 안도 검토했으나 Java 런타임 계산 상수라 `@RequestAttribute` 인자로 못 쓰고, 선례가 있어 헤더 문자열로 통일했다. 헤더 파라미터는 `OpenApiConfig` 커스터마이저가 중복 방지 가드로 한 번만 문서화한다. `OpenApiSnapshotTest` 의 "선언 버전 ⊆ 그룹" 가드는 매핑 선언만 보므로 `addSupportedVersions` 는 그룹을 요구하지 않는다 — 1.2 그룹 문서는 없고, 규칙은 홈 오퍼레이션 설명에 적는다.

## R1b. 번호는 `1.2` (2026-10-13 사용자 결정)

- 현재 앱 전역 기본 헤더가 `1.1`(kbap-fe `client.ts`)이라 다음 릴리스 마커 `1.2` 를 쓴다. 비교는 "1.2 이상" 이므로 스캔처럼 `2.0` 을 보내는 요청도 풀을 받는다(baseline 의미, 테스트로 고정). 1차의 `2.1` 은 "최고 마커 다음" 논리였으나 홈 실사용 헤더 기준으로 정정.

## R2. 설정 저장소 — yml + env 플레이스홀더 (테이블·관리자 API 없음)

- **Decision**: `kbap.home.marketing-popular.food-ids`(`HOME_MARKETING_POPULAR_FOOD_IDS`, 기본 빈 목록). 캠페인 값은 `application-prod.yml` 커밋 또는 env.
- **Rationale**: 기존 `findByIdIn` 경로를 그대로 쓰면 마이그레이션·엔티티·관리자 화면·2대 인스턴스 캐시 무효화가 전부 필요 없고, id 만 들고 원본을 매 요청 읽어 stale 이 없다(KB-413 "id 만" 원칙과 동형).
- **Alternatives considered**: 전용 테이블 + 관리자 API — 마케터가 수시 변경을 원할 때의 다음 단계로 보류.
- **바인딩 메모**: 생성자 바인딩은 `@EnableConfigurationProperties` 필요 — `PropertiesConfig` 에 추가. 빈 문자열 → 빈 목록은 `Binder` 단위 테스트로 고정.

## R3. 기간 없음·폴백 없음

- **Decision**: `starts-at`/`ends-at` 제거. 1.2 이상이면 항상 풀. 풀이 비면 빈 목록(집계 폴백 없음).
- **Rationale**: 사용자 결정 "1.2 은 항상 설정 목록, 비어 있을 일이 없다고 가정". 버전이 곧 캠페인 스위치다(새 앱 릴리스 = 노출 시작).
- **기각 이력**: 1차 `Clock` 빈 + `MutableClock` 승격(기간 밖 통합 테스트용) → 비용 대비 기각 → 기간 자체가 사라져 무의미.

## R4. 선택 — READY 풀에서 매 요청 `shuffled().take(10)`

- **Decision**: `FoodService.getRandomCuratedFoods(memberId, lang, ids, size)` — `findByIdIn(ids)` → `isReady()` 필터 → 셔플 → `take(size)`. 풀 중복은 `distinct()`.
- **Rationale**: 사용자 결정(설정 순서 앞 10 기각). READY 필터는 집계 경로(`loadInGivenOrder`/`getPopularFoods`)를 건드리지 않으려 별도 메서드. 홈의 기존 `getRandomReadyFoods` 와 같은 네이밍 규칙.
- **테스트 메모**: 랜덤이라 통합 테스트는 집합·길이로 검증한다(12 시드 → 길이 10·전부 1..12, 3 시드 → {1,2,3}).

## R5. 집계 캐시 워밍 (Codex P2, 2차 반영 → 3차에서 해제)

- 2차 설계(기간 폴백)에서는 활성 중에도 캐시를 매 요청 호출해 종료 직후 콜드 집계를 막았다. 3차에서는 폴백이 없고 1.2 미만 트래픽이 캐시를 쓰므로 1.2 경로는 캐시를 부르지 않는다. 1.2 미만 트래픽이 0 이 되는 날엔 캐시 자체가 무의미하다.

## R6. Swagger 문서

- 오퍼레이션·그룹 추가 없음. 홈 설명의 "인기 음식: 무작위 10개"(KB-714 이전 문구)를 조회 수 집계 + 1.2 이상 랜덤 풀로 현행화.
