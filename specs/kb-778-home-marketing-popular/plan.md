# Implementation Plan: 홈 인기 음식 마케팅 임시 목록

**Branch**: `feat/kb778-home-marketing-popular` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-778](https://simhani1.atlassian.net/browse/KB-778)

**Input**: Feature specification from `specs/kb-778-home-marketing-popular/spec.md`

## Summary

`GET /api/home` 에 **`X-API-Version: 2.1+` 매핑을 같은 컨트롤러에 추가**해, 설정값(`kbap.home.marketing-popular.{starts-at,ends-at,food-ids}`)이 가리키는 음식 id 목록을 설정 순서대로 `popularFoods` 에 담아 내려준다. 기간 밖·목록 비어 있음이면 기존 조회 수 집계 캐시(KB-725 `PopularFoodIdCache`)로 폴백하고, 목록의 음식은 **READY 만** 남기며 빈자리를 채우지 않는다. 무버전(1.0) 매핑과 `HomeResponse` 계약·DB 스키마는 손대지 않는다. 기간 판정은 `LocalDateTime.now(Asia/Seoul)` 직접 호출이며(2026-10-13 사용자 결정 — Clock 빈 없음), 기간 전/후·경계·미설정은 순수 함수 단위 테스트로, 기간 안 노출·비READY 제외·1.0 불변은 통합 테스트로 고정한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1(web·`@ConfigurationProperties` 생성자 바인딩), Spring Framework 7 네이티브 API 버저닝(기존 `WebConfig`), springdoc(기존 버전 그룹 문서). 신규 라이브러리 없음.

**Storage**: MySQL `food` 읽기만(`findByIdIn` 재사용). 스키마 변경·마이그레이션 없음. 임시 목록은 설정값(yml + env 플레이스홀더).

**Testing**: Kotest BehaviorSpec — 기간·중복·바인딩 규칙은 Spring 없이 단위 테스트, 버전별 응답·비READY 제외는 `@IntegrationTest`(MySQL Testcontainers, 테스트 yml 고정 창 2000~2100). 기간 밖 폴백의 통합 검증은 하지 않는다(단일 컨텍스트에서 기간을 바꿀 수 없고 Clock 빈은 기각)

**Target Platform**: api bootJar(ECS, prod 2대·dev 1대). 설정 반영 = 재배포.

**Project Type**: web-service (모듈러 모놀리스 `:api`)

**Performance Goals**: 임시 목록 적용 시 홈 1건당 인기 레일 DB 비용 = PK IN 조회 1회(기존 캐시 히트 경로와 동일). 조회 수 집계 캐시는 그대로 돌아 종료 직후 콜드 집계 없음.

**Constraints**: 1.0 매핑 무변경. 버전별 클래스 금지(같은 `HomeController` 오버로드). 새 릴리스 번호 `2.1` → `OpenApiConfig` 그룹 빈 추가 필수(`OpenApiSnapshotTest` 드리프트 가드). 통합 테스트 컨텍스트 1개 유지 — 프로퍼티 분기 금지.

**Scale/Scope**: 수정 — `HomeController`·`HomeApi`·`HomeService`·`FoodService`(메서드 1)·`OpenApiConfig`(빈 1 + 문구)·`PropertiesConfig`(애너테이션 1)·`application.yml`·테스트 `application.yml`. 신규 — `HomeMarketingPopularProperties`·테스트 2.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 단위(기간 전/중/후·경계·시작>종료·미설정·빈 목록·중복·빈 env 바인딩)와 통합(1.0 vs 2.1 차이·설정 순서·비READY/미존재 제외·전부 제외→빈 목록·타 섹션 동일)을 구현 전 Red 로 작성. 기간 밖 폴백은 단위 테스트 + `getHome` 위임 구조로 보장. |
| II. Bounded Contexts | PASS | 설정·분기는 전부 `com.kbap.api.home`(api 전용 조합). `common.domain` 무변경. 버전 해석은 web 계층, 도메인은 헤더를 모른다(위키 정책). |
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
│   └── home-marketing-popular.md   # 2.1+ 응답 계약 차이·설정 키·env
├── quickstart.md        # Phase 1 — 테스트·로컬 실측·캠페인 켜고 끄는 절차
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/home/
├── HomeController.kt                 # + @GetMapping(version = "2.1+") homeWithMarketingPopular(...)
├── HomeApi.kt                        # + 2.1 오퍼레이션(@Operation(operationId = "homeMarketingPopular")), 인기 음식 문구 현행화
├── HomeService.kt                    # + HomeMarketingPopularProperties 주입, getMarketingHome(); assembleHome 에 curated 분기
└── HomeMarketingPopularProperties.kt # 신규 — @ConfigurationProperties("kbap.home.marketing-popular") + activeFoodIds(now)
api/src/main/kotlin/com/kbap/api/food/
└── FoodService.kt                    # + getCuratedFoods(memberId, lang, ids): loadInGivenOrder + isReady 필터
api/src/main/kotlin/com/kbap/api/core/config/
├── PropertiesConfig.kt               # + @EnableConfigurationProperties(HomeMarketingPopularProperties::class)
└── OpenApiConfig.kt                  # + apiVersion21Doc 그룹 빈, 안내 문구 1.0/1.1/2.0/2.1
api/src/main/resources/application.yml                # + kbap.home.marketing-popular.* (env 플레이스홀더, 기본 비활성)
api/src/test/resources/application.yml                # + 테스트 고정 창(2000~2100)·food-ids 3,1,999,2
api/src/test/kotlin/com/kbap/api/home/
├── HomeMarketingPopularPropertiesTest.kt   # 신규 — Spring-free 단위
└── HomeMarketingPopularTest.kt             # 신규 — @IntegrationTest, 테스트 yml 고정 창 안
```

**Structure Decision**: 별도 테이블·관리자 API 없이 **설정값 + 기존 조회 경로 재사용**(2026-10-13 사용자 확인). 설정 홀더는 `api.home` 소속(배치 소비자 없음 — ADR-0016 승격 기준 미달). 1.0 과 2.1 은 `HomeService` 의 public 메서드 둘(`getHome`·`getMarketingHome`)로 갈라 컨트롤러는 호출과 매핑만 한다. READY 필터는 `FoodService` 의 별도 메서드로 둬 1.0 경로(`getPopularFoods`)를 바이트 단위로 건드리지 않는다.

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
fun getHome(memberId: Long?, lang: LanguageCode): HomeResult =
    assembleInTransaction(memberId, lang, popularFoodIdCache.getPopularFoodIds(POPULAR_SIZE), curated = false)

fun getMarketingHome(memberId: Long?, lang: LanguageCode): HomeResult {
    val curated = marketingPopular.activeFoodIds(LocalDateTime.now(SEOUL)).take(POPULAR_SIZE)
    if (curated.isEmpty()) return getHome(memberId, lang)
    return assembleInTransaction(memberId, lang, curated, curated = true)
}
// assembleHome: curated ? foodService.getCuratedFoods(...) : foodService.getPopularFoods(...)
```

```kotlin
// FoodService
@Transactional(readOnly = true)
fun getCuratedFoods(memberId: Long?, lang: LanguageCode, ids: List<Long>): List<FoodSummaryView> =
    summaryViews(loadInGivenOrder(ids).filter { it.isReady() }, lang, memberId)
```

```kotlin
// HomeController
@GetMapping(version = "2.1+")
override fun homeWithMarketingPopular(@Valid @ModelAttribute request: HomeRequest, @AuthMemberIdOrNull memberId: Long?) =
    ResponseEntity.ok(BaseResponse.ok(HomeResponse.from(homeService.getMarketingHome(memberId, LanguageCode.from(request.lang)), memberId != null)))
```

- 미존재·삭제 음식은 `findByIdIn` + `@SQLRestriction(status='ACTIVE')` 가 걸러 FR-005 의 "존재하지 않음" 분기가 공짜다. 비READY 는 `isReady()` 필터.
- 기간 밖·빈 목록은 `getHome` 으로 위임해 1.0 과 완전히 같은 경로를 탄다(FR-004).
- `assembleHome` 의 나머지(기피 성분·리뷰 인기·최근 스캔·북마크·평점)는 공유해 FR-007·FR-008 을 구조로 보장한다.
- `/api/home` 은 이미 `WebConfig` JWT 보호 경로에 있다 — 새 경로가 아니므로 등록 불필요.

## Complexity Tracking

위반 없음 — 기재 사항 없음.
