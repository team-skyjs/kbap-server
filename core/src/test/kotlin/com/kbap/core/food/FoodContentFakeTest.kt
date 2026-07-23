package com.kbap.core.food

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodContentFakeTest : BehaviorSpec({
    given("4계약을 람다 페이크(SAM)로 대체") {
        `when`("각 계약을 람다로 구현해 호출하면") {
            then("외부 호출 없이 DTO 불변을 통과한 결과를 돌려준다") {
                val nameClient = FoodNameTranslationClient { korean ->
                    TargetLanguageTexts(TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "$korean-${it.code}" })
                }
                val descriptionClient = FoodDescriptionClient { korean ->
                    FoodDescriptionContent(
                        "$korean 설명",
                        TargetLanguageTexts(TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "$korean-${it.code}" }),
                    )
                }
                val imageClient = FoodImageGenerationClient { _, storageKey -> storageKey }
                val avoidanceClient = FoodAvoidanceAssessmentClient { _, candidateCodes ->
                    FoodAvoidanceAssessmentResult(candidateCodes.map { FoodAvoidanceAssessment(it, 50) }, 3)
                }

                nameClient.call("불고기").texts.size shouldBe 9
                descriptionClient.call("불고기").description shouldBe "불고기 설명"
                imageClient.call("불고기", "food/bulgogi.jpg") shouldBe "food/bulgogi.jpg"
                avoidanceClient.call("불고기", setOf("PORK")) shouldBe
                    FoodAvoidanceAssessmentResult(listOf(FoodAvoidanceAssessment("PORK", 50)), 3)
            }
        }

        `when`("페이크가 예외를 던지면") {
            then("예외가 호출자에게 그대로 전파된다") {
                val failing = FoodNameTranslationClient { error("외부 호출 실패") }
                shouldThrow<IllegalStateException> { failing.call("불고기") }
            }
        }
    }
})
