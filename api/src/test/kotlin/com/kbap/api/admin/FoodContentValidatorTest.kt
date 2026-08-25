package com.kbap.api.admin

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.FoodIngredient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class FoodContentValidatorTest : BehaviorSpec({
    val targets = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }
    fun allTargets(prefix: String) = targets.associateWith { "$prefix-$it" }

    fun candidate(
        koreanName: String? = "된장찌개",
        description: String? = "구수한 된장찌개",
        longDescription: String? = null,
        spiciness: Int? = 3,
        nameTranslations: Map<String, String>? = allTargets("name"),
        descriptionTranslations: Map<String, String>? = allTargets("desc"),
        ingredients: List<FoodIngredient>? = listOf(FoodIngredient("SOY", 100)),
    ) = FoodContentCandidate(
        koreanName = koreanName,
        description = description,
        longDescription = longDescription,
        spiciness = spiciness,
        nameTranslations = nameTranslations,
        descriptionTranslations = descriptionTranslations,
        ingredients = ingredients,
    )

    fun codes(c: FoodContentCandidate) = FoodContentValidator.validate(c).map { it.field to it.code }

    given("음식 콘텐츠 검증기") {
        `when`("모든 규칙을 만족하면") {
            then("오류가 없다") {
                FoodContentValidator.validate(candidate()).shouldBeEmpty()
            }
        }

        `when`("이름이 정규화 후 비어 있으면") {
            then("koreanName NAME_BLANK") {
                codes(candidate(koreanName = " !! ")) shouldContainExactlyInAnyOrder listOf("koreanName" to "NAME_BLANK")
            }
        }

        `when`("이름 검증을 건너뛰면(null)") {
            then("이름 오류를 내지 않는다") {
                codes(candidate(koreanName = null)).shouldBeEmpty()
            }
        }

        `when`("설명이 비었거나 255자를 넘으면") {
            then("description DESCRIPTION_LENGTH") {
                codes(candidate(description = "")) shouldBe listOf("description" to "DESCRIPTION_LENGTH")
                codes(candidate(description = "가".repeat(256))) shouldBe listOf("description" to "DESCRIPTION_LENGTH")
            }
        }

        `when`("긴 설명이 1000자를 넘으면") {
            then("longDescription LONG_DESCRIPTION_LENGTH") {
                codes(candidate(longDescription = "가".repeat(1001))) shouldBe listOf("longDescription" to "LONG_DESCRIPTION_LENGTH")
            }
        }

        `when`("맵기가 0~10 을 벗어나면") {
            then("spiciness SPICINESS_RANGE") {
                codes(candidate(spiciness = -1)) shouldBe listOf("spiciness" to "SPICINESS_RANGE")
                codes(candidate(spiciness = 11)) shouldBe listOf("spiciness" to "SPICINESS_RANGE")
            }
        }

        `when`("번역에 대상 언어가 빠지거나 빈 문자열이면") {
            then("누락 언어를 필드명에 담아 TRANSLATION_MISSING") {
                val missingJa = allTargets("name") - "ja"
                codes(candidate(nameTranslations = missingJa)) shouldBe listOf("nameTranslations.ja" to "TRANSLATION_MISSING")

                val blankEn = allTargets("desc") + ("en" to " ")
                codes(candidate(descriptionTranslations = blankEn)) shouldBe listOf("descriptionTranslations.en" to "TRANSLATION_MISSING")
            }
        }

        `when`("재료가 누락(null)이면") {
            then("ingredients INGREDIENTS_MISSING — 빈 배열은 허용") {
                codes(candidate(ingredients = null)) shouldBe listOf("ingredients" to "INGREDIENTS_MISSING")
                codes(candidate(ingredients = emptyList())).shouldBeEmpty()
            }
        }

        `when`("재료 코드가 카탈로그에 없으면") {
            then("인덱스를 담은 필드명으로 UNKNOWN_INGREDIENT") {
                codes(candidate(ingredients = listOf(FoodIngredient("SOY", 50), FoodIngredient("PEANUTS", 30)))) shouldBe
                    listOf("ingredients[1].code" to "UNKNOWN_INGREDIENT")
            }
        }

        `when`("재료 비율이 1~100 을 벗어나면") {
            then("INCLUSION_PERCENT_RANGE") {
                codes(candidate(ingredients = listOf(FoodIngredient("SOY", 0)))) shouldBe
                    listOf("ingredients[0].inclusionPercent" to "INCLUSION_PERCENT_RANGE")
                codes(candidate(ingredients = listOf(FoodIngredient("SOY", 101)))) shouldBe
                    listOf("ingredients[0].inclusionPercent" to "INCLUSION_PERCENT_RANGE")
            }
        }

        `when`("미완성 음식(requireComplete=false)을 검증하면") {
            then("번역·재료 누락·빈 설명·미조사 맵기는 허용하되 재료 코드·비율·길이 규칙은 그대로 적용한다") {
                FoodContentValidator.validate(
                    candidate(description = "", spiciness = -1, nameTranslations = emptyMap(), descriptionTranslations = null, ingredients = null),
                    requireComplete = false,
                ).shouldBeEmpty()

                FoodContentValidator.validate(
                    candidate(ingredients = listOf(FoodIngredient("PEANUTS", 0))),
                    requireComplete = false,
                ).map { it.code } shouldContainExactlyInAnyOrder listOf("UNKNOWN_INGREDIENT", "INCLUSION_PERCENT_RANGE")

                FoodContentValidator.validate(candidate(description = "가".repeat(256)), requireComplete = false)
                    .map { it.code } shouldBe listOf("DESCRIPTION_LENGTH")
            }
        }

        `when`("여러 규칙을 동시에 어기면") {
            then("전부 모아 돌려준다") {
                val errors = FoodContentValidator.validate(candidate(description = "", spiciness = 99, ingredients = null))

                errors.map { it.field } shouldContainExactlyInAnyOrder listOf("description", "spiciness", "ingredients")
                errors.all { it.message.isNotBlank() } shouldBe true
            }
        }
    }
})
