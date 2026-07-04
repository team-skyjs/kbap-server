package com.meogo.core.food

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodTest : BehaviorSpec({
    fun substance(code: String, probability: Int) =
        FoodAvoidanceSubstance(substanceCode = AvoidanceSubstanceCodeRef(code), inclusionProbability = probability)

    val baseContent = FoodContent(
        name = LocalizedText(korean = "된장찌개"),
        description = LocalizedText(korean = "구수한 한국식 된장찌개"),
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

                food.content.name.korean shouldBe "된장찌개"
                food.content.description.korean shouldBe "구수한 한국식 된장찌개"
                food.spiciness.value shouldBe 3
            }
        }
    }

    given("Food.displayName / description — 요청 언어 표시값(ko 폴백)") {
        val localized = create(
            content = FoodContent(
                name = LocalizedText(korean = "된장찌개", translations = mapOf(LanguageCode.EN to "Doenjang Stew")),
                description = LocalizedText(korean = "구수한 된장찌개", translations = mapOf(LanguageCode.EN to "A hearty stew")),
            ),
        )

        `when`("번역이 있는 언어로 조회하면") {
            then("해당 언어 표시값을 반환한다") {
                localized.displayName(LanguageCode.EN) shouldBe "Doenjang Stew"
                localized.description(LanguageCode.EN) shouldBe "A hearty stew"
            }
        }

        `when`("번역이 없는 지원 언어로 조회하면") {
            then("한국어 원문으로 폴백한다") {
                localized.displayName(LanguageCode.JA) shouldBe "된장찌개"
                localized.description(LanguageCode.JA) shouldBe "구수한 된장찌개"
            }
        }

        `when`("한국어로 조회하면") {
            then("한국어 원문을 반환한다") {
                localized.displayName(LanguageCode.KO) shouldBe "된장찌개"
                localized.description(LanguageCode.KO) shouldBe "구수한 된장찌개"
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
                food.content.name.korean shouldBe "된장찌개"
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
