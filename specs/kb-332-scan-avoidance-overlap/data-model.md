# Data Model: v2 스캔 응답 기피성분 겹침 표시

**DB 변경 없음** — 엔티티·리포지토리·Flyway 마이그레이션 없음. 기존 데이터만 재사용한다.

## 재사용하는 기존 모델

| 모델 | 위치 | 역할 |
|------|------|------|
| `Member.profile.avoidedCodes(): Set<IngredientCode>` | `common.domain.member` | 회원 기피성분 집합 — `MemberService.getAvoidedCodes(memberId)` 로 조회(기존) |
| `Food.ingredients: List<FoodIngredient>` | `common.domain.food.model` | 음식 성분(코드 + 포함 확률) — 겹침 판정 근거 |
| `IngredientCode` | `common.domain.ingredient.model` | 성분 코드 enum(81종) — 응답 식별자·정렬 기준 |
| `Ingredient` + `IngredientJpaRepository.findByCodeIn` | `common.domain.ingredient` | 성분 카탈로그(ko 원문 + 언어별 번역) — 표시명 `displayName(lang)` 해석(음식 상세와 동일) |
| `FoodIngredient.riskLevel()` | `common.domain.food.model` | 성분별 위험도(포함 확률 10/60 임계) — 겹침 경고 수준(음식 상세 `riskStatus` 와 동일 규칙) |

## 신규/수정 모델 (in-memory 전용)

### Food (수정 — `common.domain.food.model`)

```
fun overlappedIngredients(avoidedCodes: Set<String>): List<FoodIngredient>
```

- READY 가 아니거나 `ingredients` 가 null 이면 빈 목록.
- 반환: `ingredients` 중 코드가 `avoidedCodes` 에 속하는 성분들(포함 확률·위험도 정보 보존).
- 겹침 판정에 포함 확률 임계값 없음 — 존재하면 겹침. 경고 수준은 반환된 `FoodIngredient.riskLevel()` 로 호출부가 얻는다.

### ScanResult.ItemRiskResult (수정 — `api.scan`, 내부 결과 타입)

```
val avoidances: List<AvoidanceOverlap>? = emptyList()

data class AvoidanceOverlap(
    val code: String,        // IngredientCode.name
    val name: String,        // 카탈로그 표시명 — lang 해석, 번역 부재 시 ko
    val overlapped: Boolean, // 해당 음식 성분에 포함되는가
    val riskLevel: String?,  // 겹친 경우만 SAFE/CAUTION/DANGER, 미겹침 null
)
```

- 회원 프로필 없음(게스트·온보딩 미완료): 전 항목 `null` — 기피 정보 주체 부재. `Member.profile` 은 계산 프로퍼티라 null 이 없으므로 실제 분기 신호는 `Member.onboardingCompleted = false` 다.
- matched=true(프로필 보유): 회원 기피성분 전체를 enum 선언 순서로 나열. `overlapped`·`riskLevel` 은 `Food.overlappedIngredients` 결과 기준. 기피 0개면 빈 목록.
- matched=false(미매칭·degraded, 프로필 보유): `emptyList()`.
- 기본값 `emptyList()` — v1 경로·기존 픽스처 무영향.
- 조립(ScanService): 회원 기피성분 카탈로그를 `findByCodeIn` 1건으로 로드(스캔당 1회, 항목 수와 무관) 후 항목별 매핑.

### ScanV2Response.ItemRiskResponse (수정 — `api.scan`, v2 응답 DTO)

```
val avoidances: List<AvoidanceOverlapResponse>?

data class AvoidanceOverlapResponse(
    val code: String,
    val name: String,
    val overlapped: Boolean,
    val riskLevel: String?,
)
```

- `ScanV2Response.from` 에서 `ScanResult` 의 `avoidances` 를 1:1 매핑.
- v1 `ScanResponse` 는 매핑하지 않음(계약 불변).

## 상태 전이

없음 — 조회 시점 스냅샷 계산만.
