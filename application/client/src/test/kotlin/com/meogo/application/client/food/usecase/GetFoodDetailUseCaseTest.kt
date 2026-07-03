package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.GetFoodDetailInput
import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.food.Food
import com.meogo.core.food.FoodDescriptionKind
import com.meogo.core.food.FoodIngredient
import com.meogo.core.food.FoodNotFoundException
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.Ingredient
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.UnsupportedLanguageException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class GetFoodDetailUseCaseTest : BehaviorSpec({
    val koBrief = "구수한 된장찌개"
    val koDetailed = "된장찌개는 된장을 푼 한국의 대표 찌개다."

    val doenjangStew = Food.reconstitute(
        id = 1,
        koreanName = "된장찌개",
        imageRef = "doenjang.png",
        ingredients = listOf(
            FoodIngredient(ingredient = Ingredient.reconstitute(id = 11, koreanName = "두부", iconRef = "tofu.png"), inclusionPercent = 90),
            FoodIngredient(ingredient = Ingredient.reconstitute(id = 10, koreanName = "된장", iconRef = null), inclusionPercent = 100),
        ),
        briefDescription = koBrief,
        detailedDescription = koDetailed,
    )

    fun useCase(repository: FoodRepository) =
        GetFoodDetailUseCase(repository, LanguageResolver(), MockIngredientRiskMarker())

    given("음식 상세 조회 유스케이스 — 요청 언어 우선·ko 폴백 조립") {
        `when`("요청 언어 번역이 모두 있으면") {
            then("음식명·재료명을 요청 언어로 조립하고 첫 재료에 CAUTION 을 부여한다") {
                val repository = FakeFoodRepository(
                    food = doenjangStew,
                    foodTranslations = mapOf(LanguageCode.EN to "Doenjang Stew"),
                    ingredientTranslations = mapOf(LanguageCode.EN to mapOf(10L to "Soybean paste", 11L to "Tofu")),
                )

                val result = useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "en"))

                result.name shouldBe "Doenjang Stew"
                result.imageRef shouldBe "doenjang.png"
                result.ingredients.map { it.name } shouldBe listOf("Soybean paste", "Tofu")
                result.ingredients.map { it.riskStatus } shouldBe listOf(RiskLevel.CAUTION, RiskLevel.SAFE)
                result.ingredients.map { it.inclusionPercent } shouldBe listOf(100, 90)
                result.ingredients[1].iconRef shouldBe "tofu.png"
            }
        }

        `when`("재료가 inclusionPercent 내림차순이 아닌 순서로 저장돼 있으면") {
            then("응답 재료를 inclusionPercent 내림차순으로 정렬하고 최상위에 CAUTION 을 부여한다") {
                val repository = FakeFoodRepository(food = doenjangStew)

                val result = useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "ko"))

                result.ingredients.map { it.inclusionPercent } shouldBe listOf(100, 90)
                result.ingredients.map { it.name } shouldBe listOf("된장", "두부")
                result.ingredients.map { it.riskStatus } shouldBe listOf(RiskLevel.CAUTION, RiskLevel.SAFE)
            }
        }

        `when`("lang=ko 이면") {
            then("번역을 조회하지 않고 한국어 원문을 그대로 쓴다") {
                val repository = FakeFoodRepository(food = doenjangStew)

                val result = useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "ko"))

                result.name shouldBe "된장찌개"
                result.ingredients.map { it.name } shouldBe listOf("된장", "두부")
                repository.translationLookups shouldBe 0
            }
        }

        `when`("미지원 lang 이면") {
            then("UnsupportedLanguageException 을 던진다") {
                val repository = FakeFoodRepository(food = doenjangStew)

                shouldThrow<UnsupportedLanguageException> {
                    useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "xx"))
                }
            }
        }

        `when`("일부 번역만 있으면") {
            then("번역 없는 항목만 한국어로 폴백한다") {
                val repository = FakeFoodRepository(
                    food = doenjangStew,
                    foodTranslations = emptyMap(),
                    ingredientTranslations = mapOf(LanguageCode.EN to mapOf(10L to "Soybean paste")),
                )

                val result = useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "en"))

                result.name shouldBe "된장찌개"
                result.ingredients.map { it.name } shouldBe listOf("Soybean paste", "두부")
            }
        }

        `when`("수록되지 않은 메뉴명이면") {
            then("FoodNotFoundException(\"해당 음식 정보 없음\") 을 던진다") {
                val repository = FakeFoodRepository(food = null)

                shouldThrow<FoodNotFoundException> {
                    useCase(repository).getDetail(GetFoodDetailInput("없는메뉴", "en"))
                }.message shouldBe "해당 음식 정보 없음"
            }
        }
    }

    given("음식 상세 조회 유스케이스 — 간단·자세 설명 다국어·독립 폴백") {
        `when`("요청 언어로 두 설명 번역이 모두 있으면") {
            then("간단·자세 설명을 모두 요청 언어로 조립한다") {
                val repository = FakeFoodRepository(
                    food = doenjangStew,
                    descriptionTranslations = mapOf(
                        LanguageCode.EN to mapOf(
                            FoodDescriptionKind.BRIEF to "A hearty Korean soybean paste stew.",
                            FoodDescriptionKind.DETAILED to "Doenjang-jjigae is a traditional Korean stew.",
                        ),
                    ),
                )

                val result = useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "en"))

                result.briefDescription shouldBe "A hearty Korean soybean paste stew."
                result.detailedDescription shouldBe "Doenjang-jjigae is a traditional Korean stew."
            }
        }

        `when`("lang=ko 이면") {
            then("두 설명 모두 한국어 원문으로 채우고 설명 번역을 조회하지 않는다") {
                val repository = FakeFoodRepository(food = doenjangStew)

                val result = useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "ko"))

                result.briefDescription shouldBe koBrief
                result.detailedDescription shouldBe koDetailed
                repository.descriptionLookups shouldBe 0
            }
        }

        `when`("미지원 lang 이면") {
            then("UnsupportedLanguageException 을 던진다") {
                val repository = FakeFoodRepository(food = doenjangStew)

                shouldThrow<UnsupportedLanguageException> {
                    useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "xx"))
                }
            }
        }

        `when`("지원 lang=en 인데 설명 번역 행이 전무하면") {
            then("두 설명 모두 한국어 원문으로 폴백하고 음식명·재료는 각자의 번역을 따른다") {
                val repository = FakeFoodRepository(
                    food = doenjangStew,
                    foodTranslations = mapOf(LanguageCode.EN to "Doenjang Stew"),
                    ingredientTranslations = mapOf(LanguageCode.EN to mapOf(10L to "Soybean paste", 11L to "Tofu")),
                    descriptionTranslations = emptyMap(),
                )

                val result = useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "en"))

                result.briefDescription shouldBe koBrief
                result.detailedDescription shouldBe koDetailed
                result.name shouldBe "Doenjang Stew"
                result.ingredients.map { it.name } shouldBe listOf("Soybean paste", "Tofu")
            }
        }

        `when`("간단 설명 번역만 없고 자세한 설명 번역은 있으면") {
            then("간단 설명만 한국어로 폴백하고 자세한 설명·음식명은 요청 언어를 유지한다") {
                val repository = FakeFoodRepository(
                    food = doenjangStew,
                    foodTranslations = mapOf(LanguageCode.EN to "Doenjang Stew"),
                    descriptionTranslations = mapOf(
                        LanguageCode.EN to mapOf(
                            FoodDescriptionKind.DETAILED to "Doenjang-jjigae is a traditional Korean stew.",
                        ),
                    ),
                )

                val result = useCase(repository).getDetail(GetFoodDetailInput("된장찌개", "en"))

                result.briefDescription shouldBe koBrief
                result.detailedDescription shouldBe "Doenjang-jjigae is a traditional Korean stew."
                result.name shouldBe "Doenjang Stew"
            }
        }
    }
})

private class FakeFoodRepository(
    private val food: Food?,
    private val foodTranslations: Map<LanguageCode, String> = emptyMap(),
    private val ingredientTranslations: Map<LanguageCode, Map<Long, String>> = emptyMap(),
    private val descriptionTranslations: Map<LanguageCode, Map<FoodDescriptionKind, String>> = emptyMap(),
) : FoodRepository {
    var translationLookups: Int = 0
        private set

    var descriptionLookups: Int = 0
        private set

    override fun findByKoreanName(name: String): Food? = food

    override fun findFoodNameTranslation(foodId: Long, lang: LanguageCode): String? {
        translationLookups++
        return foodTranslations[lang]
    }

    override fun findIngredientNameTranslations(ingredientIds: List<Long>, lang: LanguageCode): Map<Long, String> {
        translationLookups++
        return ingredientTranslations[lang]?.filterKeys { it in ingredientIds } ?: emptyMap()
    }

    override fun findFoodDescriptionTranslations(foodId: Long, lang: LanguageCode): Map<FoodDescriptionKind, String> {
        descriptionLookups++
        return descriptionTranslations[lang] ?: emptyMap()
    }
}
