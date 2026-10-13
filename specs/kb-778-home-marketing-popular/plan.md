# Implementation Plan: 홈 인기 음식 마케팅 임시 목록

**Branch**: `feat/kb778-home-marketing-popular` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-778](https://simhani1.atlassian.net/browse/KB-778)

**Input**: Feature specification from `specs/kb-778-home-marketing-popular/spec.md`

## Summary

기존 `GET /api/home` 의 `popularFoods` 를 **설정값(`kbap.home.marketing-popular.{starts-at,ends-at,food-ids}`)이 가리키는 음식 id 목록으로 대체**한다(2026-10-13 정정 — 별도 버전 매핑 없음, 전 버전 공통). 기간 밖·목록 비어 있음이면 기존 조회 수 집계 캐시(KB-725 `PopularFoodIdCache`)를 쓰고, 목록의 음식은 **READY 만** 남기며 빈자리를 채우지 않는다. 경로·`HomeResponse` 계약·DB 스키마는 손대지 않는다. 기간 판정은 `LocalDateTime.now(Asia/Seoul)` 직접 호출이며(2026-10-13 사용자 결정 — Clock 빈 없음), 기간 전/후·경계·미설정은 순수 함수 단위 테스트로, 기간 안 노출·비READY 제외·1.0 불변은 통합 테스트로 고정한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1(web·`@ConfigurationProperties` 생성자 바인딩). 신규 라이브러리 없음.

**Storage**: MySQL `food` 읽기만(`findByIdIn` 재사용). 스키마 변경·마이그레이션 없음. 임시 목록은 설정값(yml + env 플레이스홀더).

**Testing**: Kotest BehaviorSpec — 기간·중복·바인딩 규칙은 Spring 없이 단위 테스트. 활성/비활성 동작·비READY 제외는 `@IntegrationTest` 에서 **활성·종료 설정 인스턴스로 `HomeService` 를 직접 조립**해 검증한다(공유 컨텍스트의 테스트 yml 은 비활성 창 — 기존 홈 테스트가 집계를 보게 둔다)

**Target Platform**: api bootJar(ECS, prod 2대·dev 1대). 설정 반영 = 재배포.

**Project Type**: web-service (모듈러 모놀리스 `:api`)

**Performance Goals**: 임시 목록 적용 시 홈 1건당 인기 레일 DB 비용 = PK IN 조회 1회(기존 캐시 히트 경로와 동일). 조회 수 집계 캐시는 그대로 돌아 종료 직후 콜드 집계 없음.

**Constraints**: 경로·버전·응답 계약 무변경. 통합 테스트 컨텍스트 1개 유지 — 프로퍼티 분기 금지(활성 동작은 서비스 직접 조립으로). 조회 수 집계 캐시는 활성 중에도 매 요청 호출해 데워 둔다(Codex 리뷰 반영).

**Scale/Scope**: 수정 — `HomeService`(getHome 분기)·`FoodService`(메서드 1)·`HomeApi`(설명 문구)·`PropertiesConfig`(애너테이션 1)·`application.yml`·테스트 `application.yml`. 신규 — `HomeMarketingPopularProperties`·테스트 2.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 단위(기간 전/중/후·경계·시작>종료·미설정·빈 목록·중복·빈 env 바인딩)와 통합(활성 설정 순서·회원 위험도·언어·종료 설정 폴백·HTTP 비활성 집계·비READY/삭제/전부 제외)을 구현 전 Red 로 작성. |
| II. Bounded Contexts | PASS | 설정·분기는 전부 `com.kbap.api.home`(api 전용 조합). `common.domain` 무변경. |
| III. Dependency Direction | PASS | `api.home` → `api.food.FoodService` → `common.domain.food`. 새 seam·어댑터 없음. |
| IV. Persistence Ownership | PASS | 신규 쿼리 없음 — `findByIdIn` 재사용, READY 판정은 엔티티 메서드 `Food.isReady()`. 트랜잭션 경계는 `FoodService` 메서드 `@Transactional(readOnly = true)` + `HomeService.readOnlyTransaction` 기존 구조 유지. |
| V. Language Policy | PASS | lang 처리 무변경 — 이름 번역·폴백은 `summaryViews` 가 요청마다 수행. |
| 추가 제약 | PASS | 엔티티 비노출(기존 `FoodSummaryResponse`). 스키마·1.0 계약 무변경. |

**Post-design re-check (Phase 1 후)**: 유지. 인터페이스 1개 구현 1개 패턴 없음. 신규 빈은 설정 홀더 하나.

## Project Structure

### Documentation (this feature)

```text
specs/kb-778-home-marketing-popular/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 버전 번호·설정 형식·시각 소스·READY 필터 위치 결정
├── data-model.md        # Phase 1 — 설정 모델·판정 규칙(스키마 변경 없음)
├── contracts/
│   └── home-marketing-popular.md   # popularFoods 선택 규칙·설정 키·env
├── quickstart.md        # Phase 1 — 테스트·로컬 실측·캠페인 켜고 끄는 절차
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/home/
├── HomeApi.kt                        # 인기 음식 문구 현행화(조회 수 집계 + 마케팅 기간 대체)
├── HomeService.kt                    # + HomeMarketingPopularProperties 주입, getHome 에서 활성이면 curated 목록, assembleHome 에 curated 분기
└── HomeMarketingPopularProperties.kt # 신규 — @ConfigurationProperties("kbap.home.marketing-popular") + activeFoodIds(now)
api/src/main/kotlin/com/kbap/api/food/
└── FoodService.kt                    # + getCuratedFoods(memberId, lang, ids): loadInGivenOrder + isReady 필터
api/src/main/kotlin/com/kbap/api/core/config/
└── PropertiesConfig.kt               # + @EnableConfigurationProperties(HomeMarketingPopularProperties::class)
api/src/main/resources/application.yml                # + kbap.home.marketing-popular.* (env 플레이스홀더, 기본 비활성)
api/src/test/resources/application.yml                # + 비활성 창(2000-01-01~01-02)·food-ids 3,1,999,2
api/src/test/kotlin/com/kbap/api/home/
├── HomeMarketingPopularPropertiesTest.kt   # 신규 — Spring-free 단위
└── HomeMarketingPopularTest.kt             # 신규 — @IntegrationTest, 활성/종료 설정으로 HomeService 직접 조립
```

**Structure Decision**: 별도 테이블·관리자 API 없이 **설정값 + 기존 조회 경로 재사용**(2026-10-13 사용자 확인). 설정 홀더는 `api.home` 소속(배치 소비자 없음 — ADR-0016 승격 기준 미달). 분기는 `HomeService.getHome` 안에서 — 컨트롤러·경로·버전은 손대지 않는다. READY 필터는 `FoodService` 의 별도 메서드(`getCuratedFoods`)로 둬 집계 경로(`getPopularFoods`)를 건드리지 않는다.

### 핵심 설계 (research.md 요약)

```kotlin
@ConfigurationProperties("kbap.home.marketing-popular")
data class HomeMarketingPopularProperties(
    val startsAt: LocalDateTime? = null,
    val endsAt: LocalDateTime? = null,
    val foodIds: List<Long> = emptyList(),
) {
    fun activeFoodIds(now: LocalDateTime): List<Long> {
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)) return emptyList()
        if (now.isBefore(startsAt) || !now.isBefore(endsAt)) return emptyList()
        return foodIds.distinct()
    }
}
```

```kotlin
// HomeService
fun getHome(memberId: Long?, lang: LanguageCode): HomeResult {
    val popularFoodIds = popularFoodIdCache.getPopularFoodIds(POPULAR_SIZE)
    val curatedFoodIds = marketingPopular.activeFoodIds(LocalDateTime.now(SEOUL)).take(POPULAR_SIZE)
    return readOnlyTransaction.execute {
        if (curatedFoodIds.isEmpty()) assembleHome(memberId, lang, popularFoodIds, curated = false)
        else assembleHome(memberId, lang, curatedFoodIds, curated = true)
    }!!
}
// assembleHome: curated ? foodService.getCuratedFoods(...) : foodService.getPopularFoods(...)
```

```kotlin
// FoodService
@Transactional(readOnly = true)
fun getCuratedFoods(memberId: Long?, lang: LanguageCode, ids: List<Long>): List<FoodSummaryView> =
    summaryViews(loadInGivenOrder(ids).filter { it.isReady() }, lang, memberId)
```

- 미존재·삭제 음식은 `findByIdIn` + `@SQLRestriction(status='ACTIVE')` 가 걸러 FR-005 의 "존재하지 않음" 분기가 공짜다. 비READY 는 `isReady()` 필터.
- 기간 밖·빈 목록은 집계 id 로 기존 조립을 그대로 탄다(FR-004). 집계 캐시는 활성 중에도 매 요청 호출해 데워 둔다.
- `assembleHome` 의 나머지(기피 성분·리뷰 인기·최근 스캔·북마크·평점)는 공유해 FR-007·FR-008 을 구조로 보장한다.

## Complexity Tracking

위반 없음 — 기재 사항 없음.
