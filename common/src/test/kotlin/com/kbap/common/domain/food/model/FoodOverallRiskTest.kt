package com.kbap.common.domain.food.model

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.model.RiskLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodOverallRiskTest : BehaviorSpec({
    fun doenjangStew() = Food(
        koreanName = "된장찌개",
        description = "구수한 된장찌개",
        imageRef = "doenjang.png",
        spiciness = 3,
        ingredients = listOf(
            FoodIngredient(code = "SOY", inclusionPercent = 100),
            FoodIngredient(code = "WHEAT", inclusionPercent = 80),
            FoodIngredient(code = "CLAM", inclusionPercent = 50),
        ),
    )

    given("사용자 회피 성분과 음식 성분의 교집합으로 종합 위험도를 판정한다") {
        `when`("회피 성분이 확률 100 인 성분(SOY)과 겹치면") {
            then("DANGER 다") {
                doenjangStew().overallRisk(setOf("SOY")) shouldBe RiskLevel.DANGER
            }
        }
        `when`("회피 성분이 확률 50 인 성분(CLAM)과 겹치면") {
            then("CAUTION 이다") {
                doenjangStew().overallRisk(setOf("CLAM")) shouldBe RiskLevel.CAUTION
            }
        }
        `when`("회피 성분이 음식 성분과 하나도 겹치지 않으면") {
            then("교집합이 비어 SAFE 다") {
                doenjangStew().overallRisk(setOf("MILK")) shouldBe RiskLevel.SAFE
            }
        }
        `when`("회피 성분이 여러 성분(SOY·CLAM)과 겹치면") {
            then("겹친 성분들의 최악 위험도인 DANGER 다") {
                doenjangStew().overallRisk(setOf("SOY", "CLAM")) shouldBe RiskLevel.DANGER
            }
        }
        `when`("회피 성분 목록이 비어 있으면") {
            then("교집합이 비어 SAFE 다") {
                doenjangStew().overallRisk(emptySet()) shouldBe RiskLevel.SAFE
            }
        }
    }

    given("READY 인데 기피성분이 미조사(null)인 비정상 상태") {
        `when`("위험도를 판정하면") {
            then("미조사를 SAFE 로 은폐하지 않고 UNKNOWN 으로 fail-closed 한다") {
                val unassessed = Food(
                    koreanName = "된장찌개",
                    description = "구수한 된장찌개",
                    imageRef = "doenjang.png",
                    spiciness = 3,
                    ingredients = null,
                    contentStatus = FoodContentStatus.READY,
                )

                unassessed.overallRisk(setOf("SOY")) shouldBe RiskLevel.UNKNOWN
            }
        }
    }

    given("음식에 기피 성분이 하나도 없다") {
        `when`("어떤 회피 성분으로 판정해도") {
            then("교집합이 비어 SAFE 다") {
                val plainRice = Food(
                    koreanName = "흰밥",
                    description = "흰밥은 쌀로 지은 밥이다.",
                    spiciness = 0,
                    ingredients = emptyList(),
                )

                plainRice.overallRisk(setOf("SOY", "WHEAT", "CLAM")) shouldBe RiskLevel.SAFE
            }
        }
    }

    given("관계 이행 판정 등가 — 경계 확률") {
        fun soup(percent: Int, status: FoodContentStatus = FoodContentStatus.READY) = Food(
            koreanName = "경계국",
            description = "경계국",
            contentStatus = status,
            ingredients = listOf(FoodIngredient(code = "SOY", inclusionPercent = percent)),
        )

        `when`("회피 재료의 확률이 1·9·10·59·60·100 이면") {
            then("10 미만 SAFE, 10~59 CAUTION, 60 이상 DANGER 다") {
                listOf(1, 9, 10, 59, 60, 100).map { soup(it).overallRisk(setOf("SOY")) } shouldBe listOf(
                    RiskLevel.SAFE,
                    RiskLevel.SAFE,
                    RiskLevel.CAUTION,
                    RiskLevel.CAUTION,
                    RiskLevel.DANGER,
                    RiskLevel.DANGER,
                )
            }
        }

        `when`("서비스 중이 아니면") {
            then("재료와 무관하게 UNKNOWN 이다") {
                soup(100, FoodContentStatus.PENDING_REVIEW).overallRisk(setOf("SOY")) shouldBe RiskLevel.UNKNOWN
            }
        }
    }

    given("재료 교체") {
        fun food() = Food(koreanName = "교체국", description = "교체국", ingredients = null)

        `when`("null 로 교체하면") {
            then("미조사로 표시된다") {
                food().apply { replaceIngredients(null) }.ingredientsAssessed shouldBe false
            }
        }

        `when`("빈 배열로 교체하면") {
            then("조사 완료로 표시된다") {
                food().apply { replaceIngredients(emptyList()) }.ingredientsAssessed shouldBe true
            }
        }

        `when`("같은 code 가 두 번 있으면") {
            then("FOOD-014 로 거절한다") {
                val duplicated = listOf(FoodIngredient("SOY", 10), FoodIngredient("SOY", 100))
                shouldThrow<BusinessException> { food().replaceIngredients(duplicated) }.errorCode shouldBe
                    ErrorCode.FOOD_DUPLICATE_INGREDIENT
            }
        }

        `when`("상한을 넘기면") {
            then("FOOD-013 로 거절한다") {
                val tooMany = (0..Food.MAX_INGREDIENTS).map { FoodIngredient(code = "C$it", inclusionPercent = 50) }
                shouldThrow<BusinessException> {
                    food().replaceIngredients(tooMany)
                }.errorCode shouldBe ErrorCode.FOOD_TOO_MANY_INGREDIENTS
            }
        }
    }
})
