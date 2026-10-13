# Research: 홈 인기 음식 마케팅 임시 목록

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 플랜이 고른 결정과 기각한 대안이다.

## R1. 버전 분기 없음 — 기존 홈 응답에서 대체

- **Decision**: `GET /api/home` 의 기존(무버전) 매핑 하나가 전 버전에 같은 규칙을 적용한다. 별도 `X-API-Version` 매핑·오퍼레이션·Swagger 그룹을 두지 않는다.
- **Rationale**: 2026-10-13 사용자 정정 — 요구는 "홈 조회 API 의 popularFoods 를 대체"이지 별도 API 가 아니다. 클라이언트 변경 없이 서버 설정만으로 켜고 끄는 것이 목적이라 버전 분기는 오히려 앱 릴리스에 묶인다.
- **Alternatives considered**: `2.1+` 매핑을 같은 컨트롤러에 추가(1차 구현, PR #398 초기) — Jira 본문 문구를 따른 것이었으나 폐기. 기록으로 남기는 함정: 새 릴리스 번호를 열면 `OpenApiConfig` 그룹 빈·오버로드 `operationId` 가 따라오고 `OpenApiSnapshotTest` 가 이를 강제한다.

## R2. 설정 저장소 — yml + env 플레이스홀더 (테이블·관리자 API 없음)

- **Decision**: `kbap.home.marketing-popular.{starts-at,ends-at,food-ids}` 를 `application.yml` 에 env 플레이스홀더(`HOME_MARKETING_POPULAR_*`, 기본 빈 값 = 비활성)로 둔다. 캠페인 값은 `application-prod.yml` 에 커밋하거나 env 로 넣는다.
- **Rationale**: 2026-10-13 사용자 확인 — 기존 `loadInGivenOrder` 경로를 그대로 쓰면 마이그레이션·엔티티·관리자 화면·2대 인스턴스 캐시 무효화가 전부 필요 없고, id 만 들고 원본을 매 요청 읽어 stale 이 없다(KB-413 "id 만" 원칙과 동형). 종료는 `ends-at` 경과로 자동.
- **Alternatives considered**: 전용 테이블 + 관리자 API — 마케터가 수시 변경을 원할 때의 다음 단계로 보류. `@Value` 5개 주입 — `PropertiesConfig` 선례지만 `List<Long>`·`LocalDateTime?` 바인딩은 `@ConfigurationProperties` 생성자 바인딩이 더 짧고 타입 안전(`FoodVectorProperties` 선례).
- **바인딩 메모**: `@ConfigurationProperties` 생성자 바인딩은 `@Component` 로는 못 켜고 `@EnableConfigurationProperties` 가 필요 — 기존 `PropertiesConfig` 에 애너테이션 하나 추가. 빈 문자열 env(`HOME_MARKETING_POPULAR_STARTS_AT:` 기본)가 `LocalDateTime?` → null, `List<Long>` → 빈 목록으로 바인딩되는지 **`Binder` 단위 테스트로 고정**한다(Spring 의 `ParserConverter` 는 공백 텍스트를 null 로 돌려주지만 문서로 확신하지 않는다).

## R3. 시각 소스 — `LocalDateTime.now(Asia/Seoul)` 직접 호출 (Clock 빈 없음)

- **Decision**: `HomeService` 가 `LocalDateTime.now(ZoneId.of("Asia/Seoul"))` 로 기간을 판정한다. `Clock` 빈·테스트 `MutableClock` 은 두지 않는다(2026-10-13 사용자 결정).
- **Rationale**: 기간 판정은 순수 함수(`activeFoodIds(now)`)라 전/후·경계·미설정·역전·빈 목록을 단위 테스트로 전부 고정할 수 있고, 폴백은 `getHome` 위임이라 통합에서 따로 틀릴 여지가 없다. Clock 빈은 "기간 밖 폴백 통합 테스트" 하나를 위해 설정 클래스·testFixtures 이동·`IntegrationTest` 변경을 요구해 비용이 더 컸다.
- **Alternatives considered**: `Clock` 빈 + batch `MutableClock` 승격 — Jira DoD 문구("통합 테스트: 기간 밖 폴백")에는 맞지만 기각. PR 본문에 "기간 밖은 단위 테스트" 를 명시한다. 프로퍼티 분기용 두 번째 컨텍스트 — KB-392 금지.
- **통합 테스트 전제**: 테스트 yml 창은 비활성(2000-01-01~01-02)이라 기존 홈 테스트는 집계를 본다. 활성·종료 동작은 `HomeMarketingPopularTest` 가 실제 빈들로 `HomeService` 를 직접 조립(활성/종료 설정 인스턴스)해 검증한다 — 컨텍스트를 늘리지 않고 기간 밖 폴백까지 통합 수준에서 잡힌다.

## R4. READY 필터 위치 — `FoodService.getCuratedFoods` 신설, 1.0 경로 무변경

- **Decision**: `loadInGivenOrder(ids).filter { it.isReady() }` 를 하는 `getCuratedFoods` 를 `FoodService` 에 추가한다. 기존 `getPopularFoods` 는 손대지 않는다.
- **Rationale**: `findPopular` 가 SQL 에서 READY 를 거르므로 1.0 경로는 재필터가 불필요하고, `loadInGivenOrder` 에 필터를 넣으면 KB-725 가 "최대 TTL 지연 감수"로 결정한 1.0 거동이 바뀐다(FR-001 위반). READY 판정은 엔티티 메서드(`Food.isReady()`)를 쓴다.
- **Alternatives considered**: `getPopularFoods(readyOnly: Boolean)` 플래그 — 호출부 가독성이 떨어져 기각. 리포지토리에 `findByIdInAndContentStatus` 파생 쿼리 — 순서 보존을 다시 해야 해 이득 없음.

## R5. 폴백 경로 — `getHome` 안의 분기

- **Decision**: `getHome` 이 집계 id 와 활성 목록을 둘 다 트랜잭션 밖에서 구한 뒤, 활성 목록이 비면(기간 밖·미설정·빈 목록·시작≥종료) 집계 id 로 기존 조립을 탄다.
- **Rationale**: FR-004 "기존 조회 수 집계 결과와 동일" 을 코드 경로 동일로 보장. 캐시 호출이 트랜잭션 밖이어야 하는 KB-725 제약(`check(!isActualTransactionActive)`)도 그대로 지켜진다.
- **Codex 리뷰 반영(P2)**: 활성 중에도 `getHome` 이 집계 캐시를 매 요청 호출한다(히트라 비용 없음). 캐시의 `refreshAfterWrite`·만료는 접근이 있어야 돌기 때문에, 캠페인이 TTL(2일)보다 길면 종료 직후 첫 요청이 콜드 집계를 밟고 실패 시 빈 섹션이 나갈 수 있었다. 버전 분기가 없어진 지금은 전 트래픽이 이 경로라 더더욱 필요하다.

## R6. 중복·상한

- **Decision**: 중복 id 는 `distinct()`(첫 등장 유지), 상한은 `HomeService.POPULAR_SIZE`(10)를 `take` 로 적용.
- **Rationale**: 상한 상수를 한 곳(HomeService)에 유지. 상한 적용 후 READY 필터가 돌므로 비READY 제외로 10 미만이 될 수 있고, 이는 스펙 "빈자리 채우지 않음" 그대로다.

## R7. Swagger 문서

- **Decision**: 기존 홈 오퍼레이션 설명의 "인기 음식: 무작위 10개" 문구(현행 아님 — KB-714 이후 조회 수 집계)를 조회 수 집계 + 마케팅 기간 대체 규칙으로 현행화한다. 오퍼레이션·그룹 추가 없음.
