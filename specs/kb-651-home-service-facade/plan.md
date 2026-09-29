# Implementation Plan: 홈 화면 조합 서비스 퍼사드화

**Branch**: `kb-651-home-service-facade` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-651-home-service-facade/spec.md`

## Summary

`HomeService` 를 세 서비스(`MemberService`·`IngredientService`·`FoodService`)만 의존하는 퍼사드로 바꾼다. 네 섹션은 각각 서비스 호출 하나로 채워지고, id 재조회·순서 유지·카드 변환은 `FoodService` 의 기존 private 헬퍼(`loadInGivenOrder`·`summaryViews`)로 내려간다. 리뷰 많은 음식 조회는 `ReviewService` 에서 `FoodService.getMostReviewedFoods` 로 이관하고, 최근 스캔도 같은 선례(`getScannedFoodPage`)에 따라 `FoodService.getRecentScannedFoods` 가 소유한다. 기피 성분 섹션은 **이미 존재하는** `IngredientService` 에 `getAvoidedIngredients` 를 추가해 얻는다 — 새 서비스 클래스는 필요 없다. `HomeResult` 구조와 HTTP 응답은 불변이고, `HomeControllerTest`·`HomeGuestTest` 는 무수정으로 통과해야 한다.

## 추천 명명 (사용자 요청 항목)

| 대상 | 추천 | 비고 |
|------|------|------|
| 기피 성분 소유 서비스 | **신규 클래스 없음** — 기존 `com.kbap.api.ingredient.IngredientService` 재사용 | 같은 도메인의 두 번째 서비스는 "어디에 넣나" 를 영구 질문으로 만든다. 굳이 새 클래스를 원하면 `AvoidedIngredientService`(같은 패키지). |
| `IngredientService` 메서드 | `getAvoidedIngredients(memberId: Long?, lang: LanguageCode): List<AvoidedIngredientView>` | 목록 조회 `get~s` + KB-213 이후 용어(ingredient) |
| `FoodService` 메서드 ① | `getPopularFoods(memberId: Long?, lang: LanguageCode, size: Int): List<FoodSummaryView>` | 기존 `getRandomReadyFoods` 는 private 으로 강등 |
| `FoodService` 메서드 ② | `getMostReviewedFoods(memberId: Long?, lang: LanguageCode, size: Int): List<FoodSummaryView>` | "리뷰가 많은 음식" — `ReviewService.getMostReviewedFoodIds` 삭제 |
| `FoodService` 메서드 ③ | `getRecentScannedFoods(memberId: Long, lang: LanguageCode, size: Int): List<FoodSummaryView>` | `ScanService.getRecentReadyFoodIds` 삭제. 회원 필수 — 비회원 분기는 홈이 소유 |
| 뷰 타입 | `AvoidedSubstanceView` → `com.kbap.api.ingredient.AvoidedIngredientView` | 필드 불변. 이름 유지·이동만도 무방(research D5) |
| 인자 순서 | `(memberId, lang, size)` | 기존 `getScannedFoodPage(memberId, lang, cursor)` 와 동일 |

결정 근거는 [research.md](research.md) D1~D7.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM(Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1(web·data-jpa), Kotest BehaviorSpec + MySQL Testcontainers, ArchUnit(`arch` 태그)

**Storage**: MySQL — 스키마·마이그레이션·리포지토리 변경 없음(api 서비스 배선만 변경)

**Testing**: 기존 `HomeControllerTest`·`HomeGuestTest`(`@IntegrationTest`) 무수정 통과가 유일한 수용 기준. 신규 테스트 없음(research D6)

**Target Platform**: `:api` bootJar — 신규 모듈·패키지 없음

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스(기존 구조 유지)

**Performance Goals**: 해당 없음 — 동작 동일성이 목표. 회원 PK 단건 조회가 섹션당 1회씩 늘어나는 것은 감수(spec Assumptions)

**Constraints**: `HomeResult`·HTTP 응답 불변 · 홈 테스트 두 파일 diff 0 · `FoodService` 에 `ReviewService`/`ScanService` 주입 금지(순환) · Kotlin 주석 금지 · 서비스 public 메서드 `@Transactional` 명시

**Scale/Scope**: 수정 파일 7개(HomeService·FoodService·IngredientService·ReviewService·ScanService·HomeResult·HomeResponse) + 이동 1개(AvoidedSubstanceView → AvoidedIngredientView). 순수 코드 변경 약 ±60줄

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 이 작업은 Red→Green→**Refactor** 의 Refactor 단계다. 새 동작이 없어 Red 가 없고, 기존 홈 통합 테스트(네 섹션 전 시나리오)가 회귀 방어다. 리팩토링 전 기준선 그린 확인 → 구현 → 같은 스위트 그린 확인 순으로 진행한다(quickstart). |
| II. Bounded Contexts | PASS | `common.domain` 간 의존 변화 없음. 변화는 전부 `com.kbap.api.<feature>` 조합 계층 안이며, 그 계층은 도메인 방향 맵의 대상이 아니다. 새 api 서비스 간 의존(`IngredientService → MemberService`)은 순환을 만들지 않는다(data-model). |
| III. Layered Dependency Direction | PASS | api → common 방향 그대로. `FoodService` 의 `ReviewJpaRepository` 주입은 api → common.domain 방향이다. |
| IV. Persistence Ownership | PASS | 리포지토리 위치·가시성 불변. `FoodService` 가 리뷰·스캔 리포지토리를 직접 쓰는 것은 "단순 영속 접근은 리포지토리 직접" 조항 그대로이며 위임 전용 창구는 만들지 않는다(오히려 홈의 저장소 직접 접근이 도메인 서비스로 정리된다). 트랜잭션 경계는 각 서비스 메서드가 명시 선언한다. |
| V. Language Policy | PASS | lang 은 확정 `LanguageCode` 로 그대로 흐르고 폴백 규칙은 `displayName(lang)` 안에 있어 불변. |

**게이트 판정**: PASS. Complexity Tracking 기록 사항 없음.

**Phase 1 재검토**: 설계 후 변화 없음 — 파일 이동(D5)은 api 기능 패키지 간 이동이라 원칙 II·III 에 영향이 없다. PASS 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-651-home-service-facade/
├── spec.md              # /speckit-specify 출력
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 조사 결과 + 설계 결정 D1~D7
├── data-model.md        # Phase 1 — 스키마 변경 없음, 결과 타입·시그니처·의존 그래프 변화
├── quickstart.md        # Phase 1 — 검증 절차
└── tasks.md             # /speckit-tasks 출력 (이 커맨드가 만들지 않음)
```

`contracts/` 는 만들지 않는다 — HTTP 경로·헤더·응답 DTO 가 전부 불변이다(SC-005).

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── home/
│   ├── HomeService.kt              # 의존 5→3, getHome 본문을 섹션당 호출 1개로 축소
│   ├── HomeResult.kt               # import 만: AvoidedIngredientView
│   ├── HomeResponse.kt             # import 만: AvoidedSubstanceResponse.from(view: AvoidedIngredientView)
│   ├── HomeController.kt           # 변경 없음
│   └── AvoidedSubstanceView.kt     # 삭제(ingredient 로 이동)
├── ingredient/
│   ├── IngredientService.kt        # + MemberService 주입, + getAvoidedIngredients
│   └── AvoidedIngredientView.kt    # 신규(구 AvoidedSubstanceView, 필드 동일)
├── food/
│   └── FoodService.kt              # + ReviewJpaRepository 주입, + getPopularFoods·getMostReviewedFoods·getRecentScannedFoods,
│                                   #   getRandomReadyFoods → private
├── review/
│   └── ReviewService.kt            # − getMostReviewedFoodIds
└── scan/
    └── ScanService.kt              # − getRecentReadyFoodIds

api/src/test/kotlin/com/kbap/api/home/
├── HomeControllerTest.kt           # 변경 없음 (SC-001)
├── HomeGuestTest.kt                # 변경 없음 (SC-001)
└── HomeTestSeed.kt                 # 변경 없음
```

**Structure Decision**: 기존 `com.kbap.api.<feature>` 기능 패키지 안에서만 움직인다. 새 패키지·모듈·설정 없음.

## 구현 순서 (tasks 생성 시 기준)

1. 기준선: `./gradlew :api:test` 그린 확인.
2. `ingredient/AvoidedIngredientView.kt` 신설 → `HomeResult`·`HomeResponse` 참조 교체 → `home/AvoidedSubstanceView.kt` 삭제. 컴파일 확인.
3. `IngredientService.getAvoidedIngredients` 추가(+`MemberService` 주입).
4. `FoodService` 에 `ReviewJpaRepository` 주입, 메서드 3개 추가, `getRandomReadyFoods` private.
5. `HomeService` 를 D1 형태로 교체.
6. `ReviewService.getMostReviewedFoodIds`·`ScanService.getRecentReadyFoodIds` 삭제(미사용 import 정리).
7. quickstart 전 항목 실행 — 홈 테스트 diff 0 · grep 0건 · `./gradlew :api:test` 그린.

## Complexity Tracking

위반 없음 — 기록 사항 없음.
