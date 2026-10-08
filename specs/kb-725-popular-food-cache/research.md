# Research: 홈 인기 음식 목록 캐시 (KB-725)

## R1. 캐시 구현 — Caffeine `Cache` + 수동 `ReentrantLock`

- **Decision**: Caffeine `Cache<Int, List<Long>>`(`expireAfterWrite(2일)`, `Ticker` 주입)를 두고, 미스 구간은 `ReentrantLock` + 더블 체크로 감싸 **호출 스레드가 호출 트랜잭션 안에서** 로더를 1회 실행한다. 대기 스레드는 락 해제 후 캐시 값을 읽는다.
- **Rationale**: Jira DoD 가 Caffeine·ReentrantLock 을 명시했고, 아래 대안들이 각각 실제 함정을 가진다. dev 는 Tomcat 가상 스레드(KB-653)이고 JDK 21 은 `synchronized` 블록 안에서 가상 스레드를 캐리어에 핀 고정하므로 모니터 대신 `ReentrantLock`(park 기반) 을 쓴다.
- **Alternatives considered**:
  - **Caffeine `LoadingCache.get(key)` / `Cache.get(key, loader)`** — 키 단위 single-flight 를 내장하지만 `ConcurrentHashMap.compute` 의 bin `synchronized` 안에서 로더가 돈다. 가상 스레드에서는 로더(0.2~1.8초)와 대기자 전부가 핀 고정돼 캐리어 스레드를 소진한다. 기각.
  - **Caffeine `AsyncLoadingCache`** — 대기자는 `CompletableFuture.join` 으로 park 돼 핀 문제는 없지만, 로더가 **다른 스레드**에서 돌아 두 번째 DB 커넥션을 잡는다. 풀(10) 포화 상태에서 요청들이 각자 커넥션을 쥔 채 로더를 기다리고 로더는 커넥션을 못 얻는 교착이 생긴다 — 부하 테스트가 재현한 바로 그 조건. 기각.
  - **Spring Cache 추상화 `@Cacheable(sync = true)`** — Caffeine `CacheManager` 위에서 결국 `cache.get(key, loader)` 로 내려가 위 핀 문제를 그대로 가진다. `@EnableCaching`·`CacheManager` 빈·spring-boot-starter-cache 가 더 붙어 파일만 늘어난다. 기각.
  - **의존성 없이 `@Volatile` 항목 + 만료 시각 + `ReentrantLock`** — 가장 작다(약 15줄). TTL 은 `Ticker`/`Clock` 비교 두 줄. DoD 가 Caffeine 을 명시하지 않았다면 이쪽을 골랐을 것이다. Caffeine 이 주는 실익은 `Ticker` 기반 만료 테스트와 검증된 만료 처리뿐이며, 그 차이는 작다. DoD 명시로 Caffeine 채택.
  - **Redis 공유 캐시** — Jira 가 명시적으로 배제. 인스턴스 2대가 TTL 당 각자 1회 집계해도 비용이 미미하고, Redis 왕복·직렬화·장애 지점이 추가된다. 기각.

## R2. 캐시 보관 단위 — 음식 id 목록 (엔티티 아님)

- **Decision**: `findPopular` 결과에서 `id` 만 뽑아 순서대로 보관한다. 요청 시 `FoodService.loadInGivenOrder(ids)`(기존 private — `findByIdIn` 후 주어진 순서로 재배열)로 엔티티를 다시 읽어 `summaryViews` 에 넘긴다.
- **Rationale**: (1) `@SQLRestriction(status='ACTIVE')` 가 재조회에서 삭제 음식을 자동으로 거른다 — FR-008 이 코드 한 줄 없이 만족된다. (2) 분리된 JPA 엔티티(EAGER 성분 포함)를 2일간 힙에 쥐고 있으면 성분·번역·이미지 변경이 TTL 동안 고정된다. id 만 두면 표시 내용은 항상 최신 DB 값이다(KB-413 "벡터 스토어 = id 만 반환" 과 같은 원칙). (3) PK IN 10건 조회는 수 ms.
- **Alternatives considered**: `List<Food>` 를 그대로 캐시 — 재조회 1회를 아끼지만 위 (1)(2) 를 잃는다. 기각.

## R3. TTL 2일, 집계 윈도우 30일 — companion 상수

- **Decision**: `TTL = Duration.ofDays(2)`(2026-10-08 사용자 지시), `WINDOW_DAYS = 30L`(KB-714 값을 `FoodService` 에서 캐시 클래스로 이동). 프로퍼티로 노출하지 않는다.
- **Rationale**: 바꿀 일이 생기면 상수 한 줄 수정이다. 설정화는 요청되지 않은 유연성이다. 배포 때마다 캐시가 비므로 실제 갱신 주기는 2일 또는 배포 간격 중 짧은 쪽이다.
- **Alternatives considered**: `kbap.home.popular-cache-ttl` 프로퍼티 — 환경별로 다르게 둘 이유가 없다. 기각.

## R4. `Ticker` 주입 — 보조 생성자

- **Decision**: 주 생성자 `internal constructor(ticker: Ticker, loader: (Int) -> List<Long>)` 는 테스트용, `@Autowired constructor(foodRepository: FoodJpaRepository)` 보조 생성자가 운영 조립(`Ticker.systemTicker()` + `findPopular` 로더)을 한다.
- **Rationale**: 단위 테스트는 Spring 없이 가짜 Ticker 를 전진시켜 TTL 경과를 재현하고, 카운팅 로더로 집계 횟수를 센다. `FoodJpaRepository` 를 모킹하지 않는다 — 카탈로그에 mockk·mockito-kotlin 이 없고 인터페이스가 크다. Spring 이 Kotlin 기본 인자를 생성자 주입에서 해석하는지에 기대지 않는다(명시 보조 생성자가 확실하다).
- **Alternatives considered**: `Ticker` 빈 등록 — 운영 코드에 테스트 전용 빈이 생긴다. 기각. `FoodService` 가 내부에서 캐시를 생성 — 테스트가 캐시를 비울 손잡이가 없어진다(R5). 기각.

## R5. 통합 테스트 격리 — `invalidateAll()` 을 `beforeContainer` 에서 호출

- **Decision**: 캐시 클래스에 `invalidateAll()` 을 두고, 홈 통합 테스트(`HomeControllerTest`·`HomeGuestTest`·신규 `HomePopularCacheTest`)의 `beforeContainer` 에서 `HomeTestSeed.reset` 옆에 호출한다.
- **Rationale**: api 통합 테스트는 한 Spring 컨텍스트(= 한 JVM 캐시)를 전 클래스가 공유한다(KB-392). TTL 이 2일이라 비우지 않으면 앞 테스트가 캐시한 id 목록(예: 7건)이 뒤 테스트(13건 시드 → 10건 기대)에 그대로 남아 실패한다. 페이크가 `beforeSpec` 에서 `reset()` 하는 기존 패턴과 같다. 검증 본문은 손대지 않으므로 FR-006 을 지킨다. `invalidateAll` 은 캐시의 정당한 공개 API 이지 테스트용 가시성 완화가 아니다.
- **Alternatives considered**: 테스트 프로파일에서 TTL 0 — 캐시가 꺼져 캐시 동작 자체를 통합 검증할 수 없다. 기각. 컨텍스트 분리 — KB-392 가 금지. 기각.

## R6. 테스트 전략

- **단위(`PopularFoodIdCacheTest`, Spring 없음)**: 두 번째 호출은 로더 미호출 / Ticker 2일 전진 후 재호출 / 빈 캐시에 50개 스레드 동시 호출(로더 200ms 지연) → 로더 1회·결과 동일 / 로더 예외 → 미캐시·다음 호출 재시도 / 빈 목록 캐시됨. 동시성 테스트는 Jira DoD 가 명시 요구한 치명 경로라 둔다(그 외 동시성 테스트 추가 금지 규칙과 충돌 없음).
- **통합(`HomePopularCacheTest`, `@IntegrationTest`)**: (a) EGG 기피 회원 A 가 홈 조회(미스) → 기피 없는 회원 B 조회(히트) → 목록 같고 A 는 DANGER·B 는 아님. (b) 캐시된 목록의 음식 하나를 `status='DELETED'` 로 바꾼 뒤 조회 → 그 음식 제외. 집계 횟수 자체는 단위 테스트가 소유하므로 통합에서 Hibernate 통계를 세지 않는다.
- **기존 홈 테스트**: beforeContainer 한 줄 추가 외 무수정 통과가 회귀 기준.

## R7. Caffeine 좌표·버전

- **Decision**: `gradle/libs.versions.toml` 에 `caffeine = { module = "com.github.ben-manes.caffeine:caffeine" }`(버전 없음), `api/build.gradle.kts` 에 `"implementation"(libs.caffeine)`. 버전은 Spring Boot BOM 이 관리한다(카탈로그 주석 규칙과 동일). `:common` 에 올리지 않는다 — 소비자가 api 뿐.
