# Data Model: 홈 조합 서비스 퍼사드화

**스키마 변경 없음.** 엔티티·리포지토리·Flyway 마이그레이션은 손대지 않는다. 바뀌는 것은 api 모듈의 결과 타입 위치와 서비스 시그니처뿐이다.

## 결과 타입(불변)

| 타입 | 위치 | 필드 | 변경 |
|------|------|------|------|
| `HomeResult` | `com.kbap.api.home` | `avoidedSubstances: List<AvoidedIngredientView>`, `popularFoods`·`mostReviewedFoods`·`recentScans: List<FoodSummaryView>` | 필드 이름·개수·순서 불변. 첫 필드의 원소 타입명만 D5 에 따라 바뀐다(필드 형태 동일). |
| `FoodSummaryView` | `com.kbap.api.food` | `foodId`·`name`·`koreanName`·`imageRef`·`spiciness`·`overallRiskStatus`·`publishedAt` | 없음 |
| `AvoidedIngredientView` (구 `AvoidedSubstanceView`) | `com.kbap.api.ingredient` (구 `com.kbap.api.home`) | `code: String`, `name: String` | 패키지 이동 + 이름 변경. 필드 불변. |
| `HomeResponse`·`AvoidedSubstanceResponse`·`FoodSummaryResponse` | `com.kbap.api.home`·`com.kbap.api.food` | HTTP 계약 | 없음(외부 계약 불변) |

## 서비스 시그니처 변화

### 추가

| 서비스 | 메서드 | 반환 |
|--------|--------|------|
| `IngredientService` | `getAvoidedIngredients(memberId: Long?, lang: LanguageCode)` | `List<AvoidedIngredientView>` |
| `FoodService` | `getPopularFoods(memberId: Long?, lang: LanguageCode, size: Int)` | `List<FoodSummaryView>` |
| `FoodService` | `getMostReviewedFoods(memberId: Long?, lang: LanguageCode, size: Int)` | `List<FoodSummaryView>` |
| `FoodService` | `getRecentScannedFoods(memberId: Long, lang: LanguageCode, size: Int)` | `List<FoodSummaryView>` |

### 삭제·가시성 변경

| 서비스 | 메서드 | 처리 | 근거 |
|--------|--------|------|------|
| `ReviewService` | `getMostReviewedFoodIds(size)` | 삭제 | 호출자가 홈뿐, 역할이 `FoodService.getMostReviewedFoods` 로 이관 |
| `ScanService` | `getRecentReadyFoodIds(memberId, limit)` | 삭제 | 호출자가 홈뿐, 역할이 `FoodService.getRecentScannedFoods` 로 이관 |
| `FoodService` | `getRandomReadyFoods(size)` | private | 호출자가 홈뿐, `getPopularFoods` 내부 헬퍼로 강등 |

### 의존 그래프 변화 (api 기능 패키지)

```text
before                                   after
HomeService ─→ MemberService             HomeService ─→ MemberService
            ─→ FoodService                           ─→ IngredientService
            ─→ ScanService                           ─→ FoodService
            ─→ ReviewService
            ─→ IngredientJpaRepository   IngredientService ─→ MemberService (신규)
                                                          ─→ IngredientJpaRepository (기존)
FoodService ─→ ScanHistoryJpaRepository  FoodService ─→ ScanHistoryJpaRepository (기존)
            ─→ MemberService                         ─→ ReviewJpaRepository (신규)
                                                     ─→ MemberService (기존)
```

순환 없음: `ReviewService → FoodService`·`ScanService → FoodService` 는 그대로이고 `FoodService` 는 두 서비스를 모르며, `MemberService` 는 api 하위 어떤 서비스도 의존하지 않는다.

## 검증 규칙

- 네 섹션의 원소 순서·개수는 리팩토링 전과 같아야 한다(id 쿼리가 순서·READY 를 결정, `loadInGivenOrder` 가 순서 보존).
- 비회원·탈퇴 회원은 `avoidedSubstances`·`recentScans` 가 빈 목록이다(활성 회원 해석은 `HomeService` 가 한 번 수행).
