# Research: 홈 인기 음식 마케팅 임시 목록

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 플랜이 고른 결정과 기각한 대안이다.

## R1. 버전 번호 — `2.1+`

- **Decision**: `GET /api/home` 의 새 매핑은 `version = "2.1+"`. 기본(무버전) 매핑은 그대로 둔다.
- **Rationale**: 번호는 앱 릴리스 마커(위키 `api-versioning-policy`). 현재 최고 마커 `2.0`(스캔)은 이미 릴리스된 앱이 보내는 값이라, `2.0+` 에 홈을 걸면 "구 앱은 영향 없음"(FR-001)을 보장할 수 없다(클라이언트가 전 엔드포인트에 같은 헤더를 보내는 경우). 다음 마커 `2.1` 을 연다. 기본 핸들러에 `version = "1.0"` 을 못박지 않는다 — 온보딩만 1.1 로 올린 앱이 홈에서 매칭 실패한다(위키 함정 1).
- **Alternatives considered**: `2.0+` 재사용 — 클라이언트가 홈에 1.0 을 보내는 현 상태에선 안전하지만 릴리스 마커 의미가 깨지고 2.0 앱 거동이 바뀔 가능성이 있어 기각. `1.2+` — 2.0 보다 낮은 번호를 새로 여는 건 2.0 앱이 임시 목록을 받는 결과(baseline 매칭)라 기각.
- **따라오는 작업**: `OpenApiConfig` 에 `apiVersion21Doc` 그룹 빈 추가(없으면 `OpenApiSnapshotTest` "모든 선언 버전이 그룹으로 노출된다" 실패), 안내 문구 `1.0/1.1/2.0/2.1`. 오버로드 메서드의 `@Operation(operationId = "homeMarketingPopular")` 명시(springdoc operationId 충돌 — 위키 함정 2). FE 가 이 기능을 다른 릴리스 번호에 싣기로 하면 문자열 한 곳만 바꾼다.

## R2. 설정 저장소 — yml + env 플레이스홀더 (테이블·관리자 API 없음)

- **Decision**: `kbap.home.marketing-popular.{starts-at,ends-at,food-ids}` 를 `application.yml` 에 env 플레이스홀더(`HOME_MARKETING_POPULAR_*`, 기본 빈 값 = 비활성)로 둔다. 캠페인 값은 `application-prod.yml` 에 커밋하거나 env 로 넣는다.
- **Rationale**: 2026-10-13 사용자 확인 — 기존 `loadInGivenOrder` 경로를 그대로 쓰면 마이그레이션·엔티티·관리자 화면·2대 인스턴스 캐시 무효화가 전부 필요 없고, id 만 들고 원본을 매 요청 읽어 stale 이 없다(KB-413 "id 만" 원칙과 동형). 종료는 `ends-at` 경과로 자동.
- **Alternatives considered**: 전용 테이블 + 관리자 API — 마케터가 수시 변경을 원할 때의 다음 단계로 보류. `@Value` 5개 주입 — `PropertiesConfig` 선례지만 `List<Long>`·`LocalDateTime?` 바인딩은 `@ConfigurationProperties` 생성자 바인딩이 더 짧고 타입 안전(`FoodVectorProperties` 선례).
- **바인딩 메모**: `@ConfigurationProperties` 생성자 바인딩은 `@Component` 로는 못 켜고 `@EnableConfigurationProperties` 가 필요 — 기존 `PropertiesConfig` 에 애너테이션 하나 추가. 빈 문자열 env(`HOME_MARKETING_POPULAR_STARTS_AT:` 기본)가 `LocalDateTime?` → null, `List<Long>` → 빈 목록으로 바인딩되는지 **`Binder` 단위 테스트로 고정**한다(Spring 의 `ParserConverter` 는 공백 텍스트를 null 로 돌려주지만 문서로 확신하지 않는다).

## R3. 시각 소스 — `LocalDateTime.now(Asia/Seoul)` 직접 호출 (Clock 빈 없음)

- **Decision**: `HomeService` 가 `LocalDateTime.now(ZoneId.of("Asia/Seoul"))` 로 기간을 판정한다. `Clock` 빈·테스트 `MutableClock` 은 두지 않는다(2026-10-13 사용자 결정).
- **Rationale**: 기간 판정은 순수 함수(`activeFoodIds(now)`)라 전/후·경계·미설정·역전·빈 목록을 단위 테스트로 전부 고정할 수 있고, 폴백은 `getHome` 위임이라 통합에서 따로 틀릴 여지가 없다. Clock 빈은 "기간 밖 폴백 통합 테스트" 하나를 위해 설정 클래스·testFixtures 이동·`IntegrationTest` 변경을 요구해 비용이 더 컸다.
- **Alternatives considered**: `Clock` 빈 + batch `MutableClock` 승격 — Jira DoD 문구("통합 테스트: 기간 밖 폴백")에는 맞지만 기각. PR 본문에 "기간 밖은 단위 테스트" 를 명시한다. 프로퍼티 분기용 두 번째 컨텍스트 — KB-392 금지.
- **통합 테스트 전제**: 테스트 yml 창을 2000-01-01~2100-01-01 로 고정해 통합 테스트는 항상 기간 안이다.

## R4. READY 필터 위치 — `FoodService.getCuratedFoods` 신설, 1.0 경로 무변경

- **Decision**: `loadInGivenOrder(ids).filter { it.isReady() }` 를 하는 `getCuratedFoods` 를 `FoodService` 에 추가한다. 기존 `getPopularFoods` 는 손대지 않는다.
- **Rationale**: `findPopular` 가 SQL 에서 READY 를 거르므로 1.0 경로는 재필터가 불필요하고, `loadInGivenOrder` 에 필터를 넣으면 KB-725 가 "최대 TTL 지연 감수"로 결정한 1.0 거동이 바뀐다(FR-001 위반). READY 판정은 엔티티 메서드(`Food.isReady()`)를 쓴다.
- **Alternatives considered**: `getPopularFoods(readyOnly: Boolean)` 플래그 — 호출부 가독성이 떨어져 기각. 리포지토리에 `findByIdInAndContentStatus` 파생 쿼리 — 순서 보존을 다시 해야 해 이득 없음.

## R5. 폴백 경로 — `getMarketingHome` 이 `getHome` 에 위임

- **Decision**: 활성 목록이 비면(기간 밖·미설정·빈 목록·시작≥종료) `getMarketingHome` 은 `getHome` 을 그대로 호출한다.
- **Rationale**: FR-004 "기존 조회 수 집계 결과와 동일" 을 코드 경로 동일로 보장. 캐시 호출이 트랜잭션 밖이어야 하는 KB-725 제약(`check(!isActualTransactionActive)`)도 그대로 지켜진다.
- **주의**: 활성 목록이 있어도 조회 수 집계 캐시는 **건드리지 않는다**(호출도 안 함) — 캐시의 `refreshAfterWrite` 는 접근이 있어야 돈다. 캠페인 기간이 TTL(2일)보다 길면 종료 직후 2.1 첫 요청이 콜드 집계를 밟을 수 있다. 1.0 앱 트래픽이 캐시를 계속 데우므로 실무상 문제없고, 1.0 트래픽이 0 이 되는 시점엔 캐시 자체가 의미 없다 — 스펙 엣지 케이스 문구("캐시는 계속 유지·갱신")는 이 전제로 충족된다.

## R6. 중복·상한

- **Decision**: 중복 id 는 `distinct()`(첫 등장 유지), 상한은 `HomeService.POPULAR_SIZE`(10)를 `take` 로 적용.
- **Rationale**: 상한 상수를 한 곳(HomeService)에 유지. 상한 적용 후 READY 필터가 돌므로 비READY 제외로 10 미만이 될 수 있고, 이는 스펙 "빈자리 채우지 않음" 그대로다.

## R7. Swagger 문서

- **Decision**: `HomeApi` 에 2.1 오퍼레이션을 추가하고, 기존 설명의 "인기 음식: 무작위 10개" 문구(현행 아님 — KB-714 이후 조회 수 집계)를 조회 수 집계 + 2.1 임시 목록으로 현행화한다. 헤더 입력란·기본값은 `OpenApiConfig` 커스터마이저가 매핑에서 자동 생성하므로 `@Parameter` 를 달지 않는다.
