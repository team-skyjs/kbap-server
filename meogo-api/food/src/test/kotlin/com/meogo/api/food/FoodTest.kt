package com.meogo.api.food

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodTest : BehaviorSpec({
    fun foodIngredient(koreanName: String, percent: Int) =
        FoodIngredient(ingredient = Ingredient.create(koreanName = koreanName), inclusionPercent = percent)

    val validBrief = "구수한 한국식 된장찌개"
    val validDetailed = "된장찌개는 된장을 풀어 끓인 한국의 대표적인 찌개다."

    fun create(
        briefDescription: String = validBrief,
        detailedDescription: String = validDetailed,
    ) = Food.create(
        koreanName = "된장찌개",
        ingredients = listOf(foodIngredient("된장", 100)),
        briefDescription = briefDescription,
        detailedDescription = detailedDescription,
    )

    given("Food.ingredientsByInclusion") {
        `when`("재료가 포함비율 내림차순이 아닌 순서로 담겨 있으면") {
            then("포함비율 내림차순으로 정렬된 재료를 반환한다(머리가 주성분)") {
                val food = Food.create(
                    koreanName = "된장찌개",
                    ingredients = listOf(
                        foodIngredient("두부", 90),
                        foodIngredient("된장", 100),
                        foodIngredient("바지락", 50),
                    ),
                    briefDescription = validBrief,
                    detailedDescription = validDetailed,
                )

                food.ingredientsByInclusion().map { it.inclusionPercent } shouldBe listOf(100, 90, 50)
                food.ingredientsByInclusion().first().ingredient.koreanName shouldBe "된장"
            }
        }
    }

    given("Food.create — 간단·자세 설명 불변조건") {
        `when`("간단 설명이 정상이고 자세한 설명이 정상이면") {
            then("두 설명을 그대로 보존한다") {
                val food = create()

                food.briefDescription shouldBe validBrief
                food.detailedDescription shouldBe validDetailed
            }
        }

        `when`("간단 설명이 빈 문자열이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { create(briefDescription = "") }
            }
        }

        `when`("간단 설명이 공백뿐이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { create(briefDescription = "   ") }
            }
        }

        `when`("자세한 설명이 빈 문자열이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { create(detailedDescription = "") }
            }
        }

        `when`("자세한 설명이 공백뿐이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { create(detailedDescription = "   ") }
            }
        }

        `when`("간단 설명이 255자 상한을 초과하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { create(briefDescription = "가".repeat(256)) }
            }
        }

        `when`("간단 설명이 255자 상한과 같으면") {
            then("정상 생성된다") {
                create(briefDescription = "가".repeat(255)).briefDescription shouldBe "가".repeat(255)
            }
        }

        `when`("자세한 설명이 1024자 상한을 초과하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { create(detailedDescription = "나".repeat(1025)) }
            }
        }

        `when`("자세한 설명이 1024자 상한과 같으면") {
            then("정상 생성된다") {
                create(detailedDescription = "나".repeat(1024)).detailedDescription shouldBe "나".repeat(1024)
            }
        }
    }

    given("Food.reconstitute — 간단·자세 설명 불변조건") {
        fun reconstitute(
            briefDescription: String = validBrief,
            detailedDescription: String = validDetailed,
        ) = Food.reconstitute(
            id = 1,
            koreanName = "된장찌개",
            imageRef = null,
            ingredients = listOf(foodIngredient("된장", 100)),
            briefDescription = briefDescription,
            detailedDescription = detailedDescription,
        )

        `when`("두 설명이 정상이면") {
            then("두 설명을 그대로 복원한다") {
                val food = reconstitute()

                food.briefDescription shouldBe validBrief
                food.detailedDescription shouldBe validDetailed
            }
        }

        `when`("간단 설명이 공백뿐이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { reconstitute(briefDescription = "   ") }
            }
        }

        `when`("자세한 설명이 1024자 상한을 초과하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { reconstitute(detailedDescription = "나".repeat(1025)) }
            }
        }
    }
})
