# Phase 1 Data Model: 기피성분 위험도 정책 (KB-9)

**스키마/영속 변경 없음** — DB 테이블·컬럼·Flyway 마이그레이션·JPA 엔티티를 건드리지 않는다. 포함 확률·성분 코드는 이미 저장되어 있고, 위험도는 로드된 도메인 위 순수 계산이다. 아래는 **도메인 정책·메서드·DTO**의 변경 명세다.

## 1. `:core:kernel` — `RiskLevel` (변경)

위험도 어휘에 확률→위험도 매핑·심각도·집계를 단일 출처로 추가한다.

```
enum class RiskLevel(private val severity: Int) {
    SAFE(0),
    CAUTION(1),
    DANGER(2),
    UNKNOWN(-1);

    companion object {
        const val CAUTION_AT_LEAST = 10
        const val DANGER_AT_LEAST = 60

        fun fromInclusionProbability(probability: Int): RiskLevel = when {
            probability < CAUTION_AT_LEAST -> SAFE
            probability < DANGER_AT_LEAST -> CAUTION
            else -> DANGER
        }

        fun aggregate(levels: Collection<RiskLevel>): RiskLevel = when {
            levels.isEmpty() -> SAFE
            levels.any { it == UNKNOWN } -> UNKNOWN
            else -> levels.maxBy { it.severity }
        }
    }
}
```

**규칙**
- `fromInclusionProbability`: `p<10`→SAFE, `10≤p<60`→CAUTION, `p≥60`→DANGER (FR-001·경계 10·60 포함).
- `aggregate`: 빈 컬렉션→SAFE(FR-004), UNKNOWN 우선(R3·FR-007), 그 외 최악값(FR-003).
- `severity`는 SAFE/CAUTION/DANGER 최악값 비교 전용. UNKNOWN 은 `aggregate`의 우선 분기로만 처리되며 `severity(-1)`는 최댓값 계산에 도달하지 않는다.
- 임계값·심각도가 여기 한 곳 → 성분별·종합이 동일 규칙 공유(FR-010).

**검증 규칙(테스트)**: p=9→SAFE, 10→CAUTION, 59→CAUTION, 60→DANGER, 100→DANGER; aggregate([])→SAFE, aggregate([SAFE,CAUTION,DANGER])→DANGER, aggregate([SAFE,UNKNOWN])→UNKNOWN, aggregate([SAFE,SAFE])→SAFE.

## 2. `:core:food` — `FoodAvoidanceSubstance` (변경)

성분 자신의 위험도(사용자 무관)를 노출한다.

```
data class FoodAvoidanceSubstance(
    val substanceCode: AvoidanceSubstanceCodeRef,
    val inclusionProbability: Int,
) {
    fun riskLevel(): RiskLevel = RiskLevel.fromInclusionProbability(inclusionProbability)
    // ... 기존 init/invariant 유지
}
```

## 3. `:core:food` — `Food` (변경)

음식 단위 종합 위험도. 회피 코드는 food 소유 VO **`AvoidanceSubstanceCodeRef` 집합**으로 받아 avoidance enum 미의존(원칙 II) + primitive-obsession 회피(raw String 대신 타입 안전).

```
fun overallRisk(avoidedCodes: Set<AvoidanceSubstanceCodeRef>): RiskLevel {
    val targeted = avoidanceSubstances.filter { it.substanceCode in avoidedCodes }
    return RiskLevel.aggregate(targeted.map { it.riskLevel() })
}
```

**규칙**
- 대상 = `avoidanceSubstances` ∩ `avoidedCodes`(코드 문자열 일치).
- 대상 공집합(회피 성분이 음식에 없음) → `aggregate([])` → SAFE (FR-004).
- 음식이 성분 없음 → 대상 공집합 → SAFE.
- 대상들의 성분별 위험도 최악값 (FR-003).
- 현재 모든 성분이 확률 보유 → UNKNOWN 미방출(R4). UNKNOWN 은 `RiskLevel.aggregate` 레벨에서 방어적으로 정의·테스트.

**검증 규칙(테스트, 예: 된장찌개 SOY100·WHEAT80·CLAM50)**
| avoidedCodes | targeted | 성분별 | overall |
|---|---|---|---|
| `{SOY}` | SOY100 | DANGER | DANGER |
| `{CLAM}` | CLAM50 | CAUTION | CAUTION |
| `{MILK}` | ∅ | — | SAFE |
| `{SOY,CLAM}` | SOY100,CLAM50 | DANGER,CAUTION | DANGER |
| `{}` | ∅ | — | SAFE |

## 4. `:application:client` — 회피 목록 이음새 (신규)

```
interface AvoidedSubstanceProvider {
    fun avoidedCodes(): Set<AvoidanceSubstanceCode>
}

@Component
class MockAvoidedSubstanceProvider : AvoidedSubstanceProvider {
    override fun avoidedCodes(): Set<AvoidanceSubstanceCode> = MOCK
    companion object {
        val MOCK = setOf(
            AvoidanceSubstanceCode.SOY,
            AvoidanceSubstanceCode.MILK,
            AvoidanceSubstanceCode.PEANUT,
            AvoidanceSubstanceCode.SHRIMP,
            AvoidanceSubstanceCode.EGG,
        )
    }
}
```

- 목 집합은 seed 와 맞물려 종합 결과가 결정적이도록 고정(된장찌개 ∩ = {SOY} → DANGER). 최종 집합은 tasks 에서 seed·테스트와 함께 확정.
- 향후 member·인증 도입 시 이 인터페이스의 실제 구현으로 교체(엔드포인트 식별 흐름 포함), 유스케이스·도메인 불변.

## 5. `:application:client` — `GetFoodDetailUseCase` / `GetFoodDetailResult` (변경)

`GetFoodDetailResult`:
```
data class GetFoodDetailResult(
    val name: String,
    val imageRef: String?,
    val description: String,
    val spiciness: Int,
    val overallRiskStatus: RiskLevel,     // 신규
    val avoidanceSubstances: List<AvoidanceSubstanceView>,
) { /* AvoidanceSubstanceView 불변 */ }
```

`GetFoodDetailUseCase` 변경점:
- `MockAvoidanceRiskMarker` 의존 제거 → `AvoidedSubstanceProvider` 주입.
- 성분별 `riskStatus = substance.riskLevel()`(포함 확률 기반 실제값).
- `avoidedCodes = provider.avoidedCodes().map { AvoidanceSubstanceCodeRef(it.name) }.toSet()`.
- **종합 판정 대상 = 회피 ∩ resolvable(카탈로그 수록) 성분** — 표시 목록과 동일한 "판정 가능한 성분" 집합을 쓴다: `resolvableCodes = resolvable.map { it.first.substanceCode }.toSet()` → `overallRiskStatus = food.overallRisk(avoidedCodes intersect resolvableCodes)`.
- **정책 결정(카탈로그 결측 취급, 사용자 확정)**: 카탈로그 결측(소프트삭제)으로 표시 목록에서 빠진 성분은 종합 위험도 판정에서도 제외한다(표시-종합 입력 집합 일치, "판정 가능한 성분" 단일 소스). 트레이드오프: 결측된 위험 성분이 종합에서 SAFE 로 흡수될 수 있음(현 데이터에선 카탈로그가 고정 taxonomy 라 도달성 낮음). 애플리케이션이 판정 대상을 결정하고, 도메인(`Food.overallRisk`)은 넘겨받은 집합에 대한 최악값 정책만 소유한다.
- 미등록 음식 → 기존대로 `FoodException(NOT_FOUND)`(400) — 변경 없음.
- KB-47 카탈로그 결측 성분 skip 로직·언어 폴백 유지.

## 6. `:app:api` — `FoodDetailResponse` (변경)

- `payload`에 최상위 `overallRiskStatus: String` 추가(`result.overallRiskStatus.name`).
- 성분별 `riskStatus`는 실제값 매핑 유지(구조 불변).
- Swagger: "mock 위험도" → 포함 확률 기반 정책 + overall 설명으로 갱신.

## 삭제 대상

- `application/.../food/usecase/MockAvoidanceRiskMarker.kt`
- `application/.../food/usecase/MockAvoidanceRiskMarkerTest.kt`
