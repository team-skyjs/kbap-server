# Implementation Plan: 홈 인기 음식 마케팅 임시 목록

**Branch**: `feat/kb778-home-marketing-popular` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-778](https://simhani1.atlassian.net/browse/KB-778)

**Input**: Feature specification from `specs/kb-778-home-marketing-popular/spec.md`

## Summary

기존 `GET /api/home` 핸들러 하나가 요청의 `X-API-Version` 헤더 문자열을 보고(`AuthController` 의 기기 연결 분기와 같은 `ApiVersions.isAtLeast`) **1.2 이상이면** DB 풀(`home_marketing_popular_food` 테이블)의 READY 음식에서 **호출마다 무작위 최대 10개**를 `popularFoods` 로 내려주고, 미만이면 기존 조회 수 집계(KB-725 캐시)를 쓴다. 기간 설정·폴백 없음(풀은 비우지 않는다고 가정). 풀은 처음 yml/env 설정이었다가 사용자 요청으로 테이블로 옮겼다(2026-10-13 ④). `1.2` 은 `WebConfig.addSupportedVersions` 로 등록해 매핑 선언 없이 받는다. 경로·`HomeResponse` 계약·DB 스키마 무변경.

> 개정(2026-10-13): ① 상위 버전 오버로드 + 기간 + 설정 순서 → ② 버전 분기 제거 → ③ 매핑 하나에서 요청 버전으로 분기, 기간 제거, 랜덤 10, 폴백 없음(사용자 결정).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA·Flyway(기존), Spring Framework 7 API 버저닝(`ApiVersionConfigurer.addSupportedVersions`, 기존 `ApiVersions`/`SemanticApiVersionParser`). 신규 라이브러리 없음.

**Storage**: MySQL — 신규 테이블 `home_marketing_popular_food`(Flyway 1건, food_id 유니크·FK) + `food` 읽기(`findByIdIn` 재사용).

**Testing**: Kotest BehaviorSpec — `@IntegrationTest`(MockMvc 에 `X-API-Version` 헤더 지정, 풀은 테이블에 직접 INSERT). 단위 테스트 없음(설정 바인딩이 사라짐)

**Target Platform**: api bootJar(ECS, prod 2대·dev 1대). 설정 반영 = 재배포.

**Project Type**: web-service (모듈러 모놀리스 `:api`)

**Performance Goals**: 1.2 홈 1건당 인기 레일 DB 비용 = PK IN 조회 1회. 집계 캐시는 1.2 미만 트래픽이 데운다.

**Constraints**: 핸들러·경로 하나 유지(버전별 오버로드 금지 — 사용자 결정). 1.2 은 지원 버전 등록 필수(없으면 400). 통합 테스트 컨텍스트 1개 유지.

**Scale/Scope**: 수정 — `HomeController`(`@RequestHeader` 버전 파라미터)·`HomeApi`(시그니처·문구)·`HomeService`(분기)·`FoodService`(메서드 1 + 리포지토리 주입)·`WebConfig`(지원 버전 1)·`ApiVersions`(상수 1)·`HomeTestSeed`(테이블 정리). 신규 — Flyway 1·엔티티 1·리포지토리 1·통합 테스트 1.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 통합(1.2 풀 전부·12→10·풀 밖 제외·비READY/삭제/풀 행 DELETED/빈 풀/전부 제외·회원 위험도·언어·인기 외 payload 동일·1.0/1.1 집계·2.0 풀)을 구현 전 Red 로 작성. |
| II. Bounded Contexts | PASS | 풀 엔티티·리포지토리는 `common.domain.food`(음식 id 만 참조, 같은 컨텍스트). 분기는 `api.home`, 버전 판정은 `api.core`(web 계층). 도메인은 헤더를 모른다. |
| III. Dependency Direction | PASS | `api.home` → `api.food.FoodService` → `common.domain.food`. 새 seam·어댑터 없음. |
| IV. Persistence Ownership | PASS | 엔티티=도메인 모델(`HomeMarketingPopularFood`, BaseEntity 상속, JPA 연관 없음 — `foodId` 값), FK·유니크는 Flyway 스키마가 강제. 리포지토리는 public, `FoodService` 가 직접 쓴다. READY 판정은 엔티티 메서드 `Food.isReady()`. 트랜잭션 경계는 `FoodService` 메서드 `@Transactional(readOnly = true)` + `HomeService.readOnlyTransaction` 기존 구조 유지. |
| V. Language Policy | PASS | lang 처리 무변경 — 이름 번역·폴백은 `summaryViews` 가 요청마다 수행. |
| 추가 제약 | PASS | 엔티티 비노출(기존 `FoodSummaryResponse`). 응답 계약 무변경. 마이그레이션은 timestamp 버전·독립 실행. |

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
api/src/main/resources/db/migration/
└── V2026.10.13.20.42.36__home_marketing_popular_food_table.sql   # 신규 — food_id UNIQUE + FK food
common/src/main/kotlin/com/kbap/common/domain/food/
├── model/HomeMarketingPopularFood.kt       # 신규 — @Entity(foodId), BaseEntity
└── HomeMarketingPopularFoodJpaRepository.kt # 신규 — findAllFoodIds() JPQL projection
api/src/main/kotlin/com/kbap/api/core/
├── ApiVersions.kt                    # 기존 + HOME_MARKETING_POPULAR = "1.2"
└── config/WebConfig.kt               # + addSupportedVersions(ApiVersions.HOME_MARKETING_POPULAR)
api/src/main/kotlin/com/kbap/api/home/
├── HomeController.kt                 # home(...) 에 @RequestHeader(X-API-Version) apiVersion → ApiVersions.isAtLeast 로 플래그
├── HomeApi.kt                        # 시그니처 동기화, 인기 음식 문구(집계 + 1.2 이상 랜덤 풀)
└── HomeService.kt                    # getHome(memberId, lang, marketingPopular): 풀이면 캐시 생략, assembleHome 분기
api/src/main/kotlin/com/kbap/api/food/
└── FoodService.kt                    # + getRandomMarketingPopularFoods(memberId, lang, size): findAllFoodIds → findByIdIn → isReady → shuffled().take
api/src/test/kotlin/com/kbap/api/home/
├── HomeTestSeed.kt                   # reset 에 DELETE FROM home_marketing_popular_food (food 보다 먼저)
└── HomeMarketingPopularTest.kt       # 신규 — @IntegrationTest, 풀 행 INSERT, 헤더 1.2 / 1.0 / 1.1 / 2.0
```

**Structure Decision**: 풀은 **테이블**(사용자 요청 — DB 에서 직접 관리가 편함). 관리자 API 는 두지 않는다. 엔티티는 음식 컨텍스트(`common.domain.food`)에 두고 `foodId` 값만 든다. 버전 분기는 핸들러 하나 안에서 헤더 문자열로. READY 필터·랜덤은 `FoodService` 의 별도 메서드로 둬 집계 경로를 건드리지 않는다. 캐시는 두지 않는다 — 풀은 작고(수십 행) PK 조회 2회라 비용이 없고, 2대 인스턴스 무효화 문제가 아예 안 생긴다.

### 핵심 설계

```kotlin
// FoodService
@Transactional(readOnly = true)
fun getRandomMarketingPopularFoods(memberId: Long?, lang: LanguageCode, size: Int): List<FoodSummaryView> {
    val ids = homeMarketingPopularFoodRepository.findAllFoodIds()
    if (ids.isEmpty()) return emptyList()
    return summaryViews(foodRepository.findByIdIn(ids).filter { it.isReady() }.shuffled().take(size), lang, memberId)
}

// HomeService
fun getHome(memberId: Long?, lang: LanguageCode, marketingPopular: Boolean = false): HomeResult {
    val popularFoodIds = if (marketingPopular) emptyList() else popularFoodIdCache.getPopularFoodIds(POPULAR_SIZE)
    return readOnlyTransaction.execute { assembleHome(memberId, lang, popularFoodIds, marketingPopular) }!!
}
// assembleHome: marketingPopular ? foodService.getRandomMarketingPopularFoods(..., POPULAR_SIZE) : foodService.getPopularFoods(...)
```

- 미존재·삭제 음식은 `findByIdIn` + `@SQLRestriction(status='ACTIVE')` 가 걸러 자연 제외(FK 가 미존재 등록 자체를 막는다). 비READY 는 `isReady()` 필터. 풀 행의 `status=DELETED` 는 `@SQLRestriction` 으로 `findAllFoodIds` 에서 빠진다.
- 캐시 호출은 1.2 미만에서만 — 트랜잭션 밖 유지(KB-725 `check`).

## Complexity Tracking

위반 없음 — 기재 사항 없음.
