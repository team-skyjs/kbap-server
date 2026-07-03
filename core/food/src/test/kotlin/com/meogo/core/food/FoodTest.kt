package com.meogo.core.food

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodTest : BehaviorSpec({
    fun substance(code: String, probability: Int) =
        FoodAvoidanceSubstance(substanceCode = AvoidanceSubstanceCodeRef(code), inclusionProbability = probability)

    val baseContent = FoodContent(
        koreanName = "된장찌개",
        description = "구수한 한국식 된장찌개",
    )

    fun create(
        content: FoodContent = baseContent,
        spiciness: FoodSpiciness = FoodSpiciness(3),
        avoidanceSubstances: List<FoodAvoidanceSubstance> = listOf(substance("SOYBEAN", 100)),
    ) = Food.create(
        content = content,
        spiciness = spiciness,
        avoidanceSubstances = avoidanceSubstances,
    )

    given("Food.create — 구성·맵기 보존") {
        `when`("정상 값으로 생성하면") {
            then("content 와 맵기를 그대로 보존한다") {
                val food = create()

                food.content.koreanName shouldBe "된장찌개"
                food.content.description shouldBe "구수한 한국식 된장찌개"
                food.spiciness.value shouldBe 3
            }
        }
    }

    given("Food.avoidanceSubstancesByProbability") {
        `when`("포함 성분이 포함 확률 내림차순이 아닌 순서로 담겨 있으면") {
            then("포함 확률 내림차순으로 정렬된 성분을 반환한다") {
                val food = create(
                    avoidanceSubstances = listOf(
                        substance("TOFU", 90),
                        substance("SOYBEAN", 100),
                        substance("CLAM", 50),
                    ),
                )

                food.avoidanceSubstancesByProbability().map { it.inclusionProbability } shouldBe listOf(100, 90, 50)
                food.avoidanceSubstancesByProbability().first().substanceCode shouldBe AvoidanceSubstanceCodeRef("SOYBEAN")
            }
        }

        `when`("포함하는 기피 성분이 하나도 없으면") {
            then("빈 목록을 반환하고 음식은 유효하다") {
                val food = create(avoidanceSubstances = emptyList())

                food.avoidanceSubstancesByProbability() shouldBe emptyList()
                food.content.koreanName shouldBe "된장찌개"
            }
        }
    }

    given("Food.create — 포함 기피 성분 코드 유일") {
        `when`("한 음식에 같은 기피 성분 코드가 중복으로 담기면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    create(
                        avoidanceSubstances = listOf(
                            substance("SOYBEAN", 100),
                            substance("SOYBEAN", 80),
                        ),
                    )
                }
            }
        }
    }
})
