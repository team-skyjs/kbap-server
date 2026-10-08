# Implementation Plan: 홈 인기 음식 목록 캐시

**Branch**: `feat/kb725-popular-food-cache` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-725](https://simhani1.atlassian.net/browse/KB-725)

**Input**: Feature specification from `specs/kb-725-popular-food-cache/spec.md`

## Summary

홈 API 가 요청마다 돌리는 인기 음식 집계(`FoodJpaRepository.findPopular` — `food_view_log` 최근 30일 group by)를 api 프로세스 안의 Caffeine 캐시로 **TTL(2일)당 1회**로 줄인다. 캐시 항목은 **인기 음식 id 의 순서 있는 목록**(키 = 레일 크기, 사용자·언어 무관)이며, 미스 구간은 `ReentrantLock` 으로 묶어 동시 미스에서도 집계가 1회만 돈다. 사용자별 값(기피 성분 안전 여부·북마크·평점)과 언어별 이름은 캐시된 id 로 음식을 다시 읽어 기존 `summaryViews` 가 요청마다 조립한다. 홈 응답 계약·DB 스키마는 바뀌지 않는다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, **Caffeine**(신규 — `com.github.ben-manes.caffeine:caffeine`, 버전은 Spring Boot BOM 관리), Spring Data JPA(기존)

**Storage**: MySQL(기존 `food`·`food_view_log` 읽기만, 스키마 변경 없음). 캐시는 JVM 힙(인스턴스별).

**Testing**: Kotest BehaviorSpec — 캐시 단위 테스트는 Spring 없이 가짜 `Ticker`·카운팅 로더로, 사용자별 평가·삭제 음식 제외는 `@IntegrationTest`(MySQL Testcontainers)

**Target Platform**: api bootJar(ECS, 운영 2대·dev 1대). dev 는 Tomcat 가상 스레드(KB-653)

**Project Type**: web-service (모듈러 모놀리스 `:api`)

**Performance Goals**: 인스턴스당 TTL 동안 집계 1회. 홈 1건당 인기 레일 DB 비용 = PK IN 조회 1회(수 ms)

**Constraints**: 캐시 미스 집계는 **호출 스레드·호출 트랜잭션 안**에서 돈다(별도 스레드로 넘기면 풀 포화 시 두 번째 커넥션을 기다리다 교착). 대기는 `ReentrantLock`(모니터 금지 — JDK 21 가상 스레드 핀 고정 회피). 캐시 키에 memberId·lang 을 넣지 않는다.

**Scale/Scope**: 변경 파일 — 신규 1(캐시 클래스) + 수정 1(`FoodService.getPopularFoods` 한 줄) + 빌드 2(카탈로그·api build) + 테스트 신규 2·수정 2(홈 테스트 beforeContainer 에 캐시 비우기)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 캐시 단위 테스트(2회 호출→로더 1회 / TTL 경과→재집계 / 동시 N→1회 / 실패 미캐시 / 빈 목록 캐시)와 통합 테스트(캐시 히트에서 사용자별 안전 여부·삭제 음식 제외)를 구현 전에 Red 로 작성한다. 기존 홈 통합 테스트는 검증 본문 무수정. |
| II. Bounded Contexts | PASS | 캐시 클래스는 `com.kbap.api.food` 소속(api 전용 조합). `common.domain` 에 손대지 않는다. 다른 컨텍스트 참조 없음. |
| III. Dependency Direction | PASS | `api.food` → `common.domain.food.FoodJpaRepository` 방향만. Caffeine 은 api 모듈 의존성이며 common 에 올리지 않는다(소비자가 api 뿐 — ADR-0016 배치 기준). |
| IV. Persistence Ownership | PASS | 리포지토리 직접 호출(기존 `findPopular`·`findByIdIn` 재사용, 신규 쿼리 없음). `getPopularFoods` 의 `@Transactional(readOnly = true)` 유지 — 로더가 그 트랜잭션 안에서 돈다. 엔티티를 캐시에 두지 않고 id 만 둔다(KB-413 "벡터 스토어 = id 만" 과 같은 원칙). |
| V. Language Policy | PASS | lang 은 캐시 키 밖. 이름 번역·폴백은 기존 `FoodSummaryView.from` 이 요청마다 수행. |
| 추가 제약 | PASS | 도메인 모델을 응답에 그대로 노출하지 않음(변경 없음). 스키마·API 계약 변경 없음. |

**Post-design re-check (Phase 1 후)**: 위 판정 유지. 신규 추상화 없음(인터페이스 1개 구현 1개 패턴 없음), 설정값 노출 없음(TTL·윈도우는 companion 상수).

## Project Structure

### Documentation (this feature)

```text
specs/kb-725-popular-food-cache/
├── spec.md              # 요구사항
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정·대안
├── data-model.md        # Phase 1 — 캐시 항목 모델(스키마 변경 없음)
├── quickstart.md        # Phase 1 — 검증 절차(테스트·로컬·dev 부하 테스트)
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 가 생성
```

HTTP 계약이 바뀌지 않으므로 `contracts/` 는 만들지 않는다 — 홈 응답은 `HomeResponse` 그대로.

### Source Code (repository root)

```text
gradle/libs.versions.toml                                   # + caffeine 라이브러리 좌표(버전 없음, Boot BOM)
api/build.gradle.kts                                        # + "implementation"(libs.caffeine)
api/src/main/kotlin/com/kbap/api/food/
├── PopularFoodIdCache.kt                                   # 신규 — Caffeine Cache<Int, List<Long>> + ReentrantLock + invalidateAll()
└── FoodService.kt                                          # getPopularFoods: findPopular 직접 호출 → 캐시 id → loadInGivenOrder; POPULAR_WINDOW_DAYS 상수 이동
api/src/test/kotlin/com/kbap/api/food/
└── PopularFoodIdCacheTest.kt                               # 신규 — Spring 없이 가짜 Ticker·카운팅 로더
api/src/test/kotlin/com/kbap/api/home/
├── HomePopularCacheTest.kt                                 # 신규 — @IntegrationTest: 캐시 히트에서 사용자별 안전 여부 / 삭제 음식 제외
├── HomeControllerTest.kt                                   # beforeContainer 에 popularFoodIdCache.invalidateAll() 추가(검증 본문 무수정)
└── HomeGuestTest.kt                                        # 동일
```

**Structure Decision**: 캐시는 `FoodService` 의 필드가 아니라 **같은 기능 패키지의 별도 `@Component`** 로 둔다. 이유 두 가지 — (1) 단위 테스트가 가짜 `Ticker` 로 TTL 경과를 재현해야 하는데 `FoodService` 생성자에 Ticker 를 끼우면 의존이 8개로 늘고 통합 컨텍스트 조립이 흔들린다, (2) 통합 테스트 컨텍스트는 전 클래스가 한 JVM·한 캐시를 공유하므로 테스트가 캐시를 비울 손잡이(`invalidateAll`)를 빈으로 주입받아야 한다(페이크 `reset()` 과 같은 패턴).

### 핵심 설계 (research.md 요약)

```kotlin
@Component
class PopularFoodIdCache internal constructor(ticker: Ticker, private val loader: (Int) -> List<Long>) {
    @Autowired
    constructor(foodRepository: FoodJpaRepository) : this(
        Ticker.systemTicker(),
        { size -> foodRepository.findPopular(LocalDateTime.now().minusDays(WINDOW_DAYS), size).map { it.id } },
    )

    private val cache: Cache<Int, List<Long>> = Caffeine.newBuilder().expireAfterWrite(TTL).ticker(ticker).build()
    private val refresh = ReentrantLock()

    fun getPopularFoodIds(size: Int): List<Long> {
        cache.getIfPresent(size)?.let { return it }
        return refresh.withLock { cache.getIfPresent(size) ?: loader(size).also { cache.put(size, it) } }
    }

    fun invalidateAll() = cache.invalidateAll()

    companion object {
        const val WINDOW_DAYS = 30L
        val TTL: Duration = Duration.ofDays(2)
    }
}
```

```kotlin
// FoodService
@Transactional(readOnly = true)
fun getPopularFoods(memberId: Long?, lang: LanguageCode, size: Int): List<FoodSummaryView> =
    summaryViews(loadInGivenOrder(popularFoodIdCache.getPopularFoodIds(size)), lang, memberId)
```

- 로더 예외는 `put` 전에 전파되므로 실패가 캐시되지 않는다(FR-007). 빈 목록은 정상 값으로 `put` 된다.
- `loadInGivenOrder` 는 `findByIdIn` → `@SQLRestriction(status='ACTIVE')` 가 삭제 음식을 걸러 FR-008 을 공짜로 만족한다. READY 해제는 거르지 않는다(스펙이 최대 TTL 지연을 감수).
- 키는 `size`(홈 레일 10) — 전역 1개지만 다른 크기로 호출돼도 틀린 목록을 주지 않는다.
- 락은 전역 1개. 더블 체크로 락 안에서 재확인한다.

## Complexity Tracking

위반 없음 — 기재할 항목 없다.
