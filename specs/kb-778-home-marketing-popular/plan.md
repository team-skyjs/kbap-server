# Implementation Plan: 홈 인기 음식 마케팅 임시 목록

**Branch**: `feat/kb778-home-marketing-popular` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-778](https://simhani1.atlassian.net/browse/KB-778)

**Input**: Feature specification from `specs/kb-778-home-marketing-popular/spec.md`

## Summary

기존 `GET /api/home` 핸들러 하나가 요청의 `X-API-Version` 헤더 문자열을 보고(`AuthController` 의 기기 연결 분기와 같은 `ApiVersions.isAtLeast`) **2.1 이상이면** 설정 풀(`kbap.home.marketing-popular.food-ids`)의 READY 음식에서 **호출마다 무작위 최대 10개**를 `popularFoods` 로 내려주고, 미만이면 기존 조회 수 집계(KB-725 캐시)를 쓴다. 기간 설정·폴백 없음(풀은 비우지 않는다고 가정). `2.1` 은 `WebConfig.addSupportedVersions` 로 등록해 매핑 선언 없이 받는다. 경로·`HomeResponse` 계약·DB 스키마 무변경.

> 개정(2026-10-13): ① 상위 버전 오버로드 + 기간 + 설정 순서 → ② 버전 분기 제거 → ③ 매핑 하나에서 요청 버전으로 분기, 기간 제거, 랜덤 10, 폴백 없음(사용자 결정).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1(`@ConfigurationProperties` 생성자 바인딩), Spring Framework 7 API 버저닝(`ApiVersionConfigurer.addSupportedVersions`, 기존 `ApiVersions`/`SemanticApiVersionParser`). 신규 라이브러리 없음.

**Storage**: MySQL `food` 읽기만(`findByIdIn` 재사용). 스키마 변경 없음. 풀은 설정값(yml + env 플레이스홀더).

**Testing**: Kotest BehaviorSpec — env 바인딩은 Spring 없이 `Binder` 단위 테스트. 버전별 응답·랜덤 10·비READY 제외는 `@IntegrationTest`(MockMvc 에 `X-API-Version` 헤더 지정, 테스트 yml 풀 1~12+999)

**Target Platform**: api bootJar(ECS, prod 2대·dev 1대). 설정 반영 = 재배포.

**Project Type**: web-service (모듈러 모놀리스 `:api`)

**Performance Goals**: 2.1 홈 1건당 인기 레일 DB 비용 = PK IN 조회 1회. 집계 캐시는 2.1 미만 트래픽이 데운다.

**Constraints**: 핸들러·경로 하나 유지(버전별 오버로드 금지 — 사용자 결정). 2.1 은 지원 버전 등록 필수(없으면 400). 통합 테스트 컨텍스트 1개 유지.

**Scale/Scope**: 수정 — `HomeController`(`@RequestHeader` 버전 파라미터)·`HomeApi`(시그니처·문구)·`HomeService`(분기)·`FoodService`(메서드 1)·`WebConfig`(지원 버전 1)·`PropertiesConfig`(애너테이션 1)·`application.yml`·테스트 `application.yml`. 신규 — `ApiVersions`·`HomeMarketingPopularProperties`·테스트 2.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 단위(빈 env·콤마 목록 바인딩)와 통합(2.1 풀 전부·12→10·비READY/삭제/전부 제외·회원 위험도·언어·인기 외 payload 동일·1.0/2.0 집계)을 구현 전 Red 로 작성. |
| II. Bounded Contexts | PASS | 설정·분기는 `com.kbap.api.home`, 버전 판정 부품은 `com.kbap.api.core`(web 계층). `common.domain` 무변경, 도메인은 헤더를 모른다. |
| III. Dependency Direction | PASS | `api.home` → `api.food.FoodService` → `common.domain.food`. 새 seam·어댑터 없음. |
| IV. Persistence Ownership | PASS | 신규 쿼리 없음 — `findByIdIn` 재사용, READY 판정은 엔티티 메서드 `Food.isReady()`. 트랜잭션 경계는 `FoodService` 메서드 `@Transactional(readOnly = true)` + `HomeService.readOnlyTransaction` 기존 구조 유지. |
| V. Language Policy | PASS | lang 처리 무변경 — 이름 번역·폴백은 `summaryViews` 가 요청마다 수행. |
| 추가 제약 | PASS | 엔티티 비노출(기존 `FoodSummaryResponse`). 스키마·응답 계약 무변경. |

**Post-design re-check (Phase 1 후)**: 유지. 인터페이스 1개 구현 1개 패턴 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-778-home-marketing-popular/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 버전 번호·설정 형식·시각 소스·READY 필터 위치 결정
├── data-model.md        # Phase 1 — 설정 모델·판정 규칙(스키마 변경 없음)
├── contracts/
│   └── home-marketing-popular.md   # 버전별 popularFoods 선택 규칙·설정 키·env
├── quickstart.md        # Phase 1 — 테스트·로컬 실측·캠페인 켜고 끄는 절차
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/core/
├── ApiVersions.kt                    # 기존(AuthController 가 사용) + HOME_MARKETING_POPULAR = "2.1" 상수만 추가
└── config/
    ├── WebConfig.kt                  # + addSupportedVersions(ApiVersions.HOME_MARKETING_POPULAR)
    └── PropertiesConfig.kt           # + @EnableConfigurationProperties(HomeMarketingPopularProperties::class)
api/src/main/kotlin/com/kbap/api/home/
├── HomeController.kt                 # home(...) 에 @RequestHeader(X-API-Version) apiVersion 추가 → ApiVersions.isAtLeast 로 플래그
├── HomeApi.kt                        # 시그니처 동기화, 인기 음식 문구(집계 + 2.1 이상 랜덤 풀)
├── HomeService.kt                    # getHome(memberId, lang, marketingPopular): 풀 id vs 집계 id, assembleHome 분기
└── HomeMarketingPopularProperties.kt # 신규 — @ConfigurationProperties("kbap.home.marketing-popular") foodIds
api/src/main/kotlin/com/kbap/api/food/
└── FoodService.kt                    # + getRandomCuratedFoods(memberId, lang, ids, size): findByIdIn → isReady → shuffled().take(size)
api/src/main/resources/application.yml                # + kbap.home.marketing-popular.food-ids (env 플레이스홀더)
api/src/test/resources/application.yml                # + food-ids 1..12,999
api/src/test/kotlin/com/kbap/api/home/
├── HomeMarketingPopularPropertiesTest.kt   # 신규 — Binder 단위
└── HomeMarketingPopularTest.kt             # 신규 — @IntegrationTest, 헤더 2.1 / 1.0 / 2.0
```

**Structure Decision**: 별도 테이블·관리자 API 없이 **설정값 + 기존 조회 경로 재사용**. 버전 분기는 **핸들러 하나** 안에서 요청 속성으로(사용자 결정 — 오버로드 기각). 버전 해석은 컨트롤러(web 계층)가 boolean 으로 확정해 서비스에 넘기고, 서비스·도메인은 헤더를 모른다. READY 필터·랜덤 선택은 `FoodService` 의 별도 메서드로 둬 집계 경로(`getPopularFoods`)를 건드리지 않는다.

### 핵심 설계

```kotlin
// ApiVersions (api.core, 기존) — const val HOME_MARKETING_POPULAR = "2.1" 추가
fun isAtLeast(requested: String, minimum: String): Boolean = parser.parseVersion(requested) >= parser.parseVersion(minimum)

// HomeController
@GetMapping
override fun home(request, memberId, @RequestHeader(ApiHeaders.API_VERSION) apiVersion: String) {
    val marketingPopular = ApiVersions.isAtLeast(apiVersion, ApiVersions.HOME_MARKETING_POPULAR)
    homeService.getHome(memberId, LanguageCode.from(request.lang), marketingPopular) ...
}

// HomeService
fun getHome(memberId: Long?, lang: LanguageCode, marketingPopular: Boolean = false): HomeResult {
    val popularFoodIds = if (marketingPopular) marketingPopularProperties.foodIds.distinct() else popularFoodIdCache.getPopularFoodIds(POPULAR_SIZE)
    return readOnlyTransaction.execute { assembleHome(memberId, lang, popularFoodIds, marketingPopular) }!!
}
// assembleHome: marketingPopular ? foodService.getRandomCuratedFoods(..., POPULAR_SIZE) : foodService.getPopularFoods(...)

// FoodService
@Transactional(readOnly = true)
fun getRandomCuratedFoods(memberId: Long?, lang: LanguageCode, ids: List<Long>, size: Int): List<FoodSummaryView> {
    if (ids.isEmpty()) return emptyList()
    return summaryViews(foodRepository.findByIdIn(ids).filter { it.isReady() }.shuffled().take(size), lang, memberId)
}
```

- 미존재·삭제 음식은 `findByIdIn` + `@SQLRestriction(status='ACTIVE')` 가 걸러 자연 제외. 비READY 는 `isReady()` 필터.
- 캐시 호출은 2.1 미만에서만 — 트랜잭션 밖 유지(KB-725 `check`).
- `/api/home` 경로·JWT 보호 등록 변경 없음.

## Complexity Tracking

위반 없음 — 기재 사항 없음.
