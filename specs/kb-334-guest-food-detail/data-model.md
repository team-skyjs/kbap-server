# Data Model: 비회원 음식 상세 조회 응답 개편 (KB-334)

**영속 모델 변경 없음** — 엔티티·리포지토리·Flyway 마이그레이션을 손대지 않는다. 변경되는 것은 api 응답 조립 타입뿐이다.

## 변경 타입 (com.kbap.api.food)

### GetFoodDetailResult (서비스 결과)

| 필드 | 현행 | 변경 | 근거 |
|------|------|------|------|
| `overallRiskStatus` | `RiskLevel` | `RiskLevel?` — 비회원 조회 시 null | FR-001 |
| 나머지 필드 | — | 무변경 | |

### FoodDetailResponse (API 응답)

| 필드 | 현행 | 변경 | 근거 |
|------|------|------|------|
| `overallRiskStatus` | `String` | `String?` — 비회원 null | FR-001 |
| `bookmarked` | `Boolean` (비회원 false) | 무변경 | FR-002 |
| `ingredients` | `List<IngredientResponse>` (비회원 빈 배열) | 무변경 | 스펙 결정 |
| `review` | `ReviewSummaryResponse` | 구조 변경 (아래) | FR-003~005 |

### FoodDetailResponse.ReviewSummaryResponse

| 필드 | 현행 | 변경 | 근거 |
|------|------|------|------|
| `overall` | `ReviewRatingResponse` (비회원 0 고정) | 무변경 타입 — 비회원도 실제 집계값 | FR-003 |
| `sameCountry` | `ReviewRatingResponse` | `ReviewRatingResponse?` — 비회원 null, 회원은 기존 수치(국적 없으면 `{0.0, 0}`) | FR-004 |
| `blur` | `Boolean` | **삭제** (+ `blurred()` 팩토리 삭제) | FR-005 |

## 상태/분기 규칙

```text
memberId == null (비회원)          → overallRiskStatus=null · sameCountry=null · overall=실집계
memberId != null, 활성 회원        → 기존 동작 전부 유지 (blur 필드만 사라짐)
memberId != null, 조회 실패(탈퇴 등) → 비회원과 동일 취급 (기존 blurred 취급과 일관)
```
