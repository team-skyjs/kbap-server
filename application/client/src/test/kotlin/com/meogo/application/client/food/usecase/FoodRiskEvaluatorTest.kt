package com.meogo.application.client.food.usecase

import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.kernel.risk.RiskLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private class FakeAvoidedProvider(private val codes: Set<AvoidanceSubstanceCode>) : AvoidedSubstanceProvider {
    override fun avoidedCodes(): Set<AvoidanceSubstanceCode> = codes
}

private class FakeCatalogRepository(private val active: Set<AvoidanceSubstanceCode>) : AvoidanceSubstanceRepository {
    override fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> =
        codes.filter { it in active }.map {
            AvoidanceSubstance.reconstitute(id = 1L, code = it, name = LocalizedText(korean = it.name))
        }
}

class FoodRiskEvaluatorTest : BehaviorSpec({
    fun readyFood(id: Long, vararg substances: Pair<String, Int>) = Food.reconstitute(
        id = id,
        content = FoodContent(
            name = LocalizedText(korean = "메뉴$id"),
            description = LocalizedText(korean = "설명"),
        ),
        imageRef = null,
        spiciness = FoodSpiciness(0),
        avoidanceSubstances = substances.map {
            FoodAvoidanceSubstance(AvoidanceSubstanceCodeRef(it.first), it.second)
        },
    )

    fun evaluator(
        avoided: Set<AvoidanceSubstanceCode> = setOf(AvoidanceSubstanceCode.SOY),
        catalog: Set<AvoidanceSubstanceCode> = setOf(AvoidanceSubstanceCode.SOY),
    ) = FoodRiskEvaluator(FakeAvoidedProvider(avoided), FakeCatalogRepository(catalog))

    given("FoodRiskEvaluator 위험도 산출") {
        `when`("완성된 음식이 사용자의 회피 성분을 높은 확률로 포함하면") {
            then("DANGER 로 판정한다") {
                val risks = evaluator().risksOf(listOf(readyFood(1L, "SOY" to 100)))
                risks.getValue(1L) shouldBe RiskLevel.DANGER
            }
        }

        `when`("완성된 음식이 회피 성분을 포함하지 않으면") {
            then("SAFE 로 판정한다") {
                val risks = evaluator().risksOf(listOf(readyFood(1L, "MILK" to 100)))
                risks.getValue(1L) shouldBe RiskLevel.SAFE
            }
        }

        `when`("회피 성분이 카탈로그에서 삭제됐으면") {
            then("판정 대상에서 빠져 SAFE 가 된다") {
                val risks = evaluator(catalog = emptySet()).risksOf(listOf(readyFood(1L, "SOY" to 100)))
                risks.getValue(1L) shouldBe RiskLevel.SAFE
            }
        }

        `when`("미완성(INCOMPLETE) 음식이면") {
            then("성분이 비어 있어도 UNKNOWN 이다") {
                val incomplete = Food.reconstitute(
                    id = 9L,
                    content = FoodContent(
                        name = LocalizedText(korean = "우주라면"),
                        description = LocalizedText(korean = Food.PLACEHOLDER_DESCRIPTION),
                    ),
                    imageRef = null,
                    spiciness = FoodSpiciness(0),
                    avoidanceSubstances = emptyList(),
                    contentStatus = com.meogo.core.food.FoodContentStatus.INCOMPLETE,
                )

                evaluator().risksOf(listOf(incomplete)).getValue(9L) shouldBe RiskLevel.UNKNOWN
            }
        }

        `when`("음식이 없으면") {
            then("빈 맵을 반환한다") {
                evaluator().risksOf(emptyList()) shouldBe emptyMap()
            }
        }
    }
})
