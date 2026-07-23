package com.kbap.core.food

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodContentDtoTest : BehaviorSpec({
    fun validTranslations() =
        TargetLanguageTexts(TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "번역-${it.code}" })

    given("FoodDescriptionContent 생성") {
        `when`("설명·번역이 정상이면") {
            then("생성에 성공한다") {
                val content = FoodDescriptionContent("불고기 설명", validTranslations())
                content.description shouldBe "불고기 설명"
            }
        }

        `when`("설명이 blank 이면") {
            then("예외가 발생한다") {
                shouldThrow<IllegalArgumentException> {
                    FoodDescriptionContent("  ", validTranslations())
                }
            }
        }

        `when`("설명이 255자를 넘으면") {
            then("예외가 발생한다") {
                shouldThrow<IllegalArgumentException> {
                    FoodDescriptionContent("가".repeat(256), validTranslations())
                }
            }
        }

        `when`("설명이 플레이스홀더면") {
            then("예외가 발생한다") {
                shouldThrow<IllegalArgumentException> {
                    FoodDescriptionContent(FoodDescriptionContent.PLACEHOLDER_DESCRIPTION, validTranslations())
                }
            }
        }
    }

    given("FoodAvoidanceAssessmentResult 생성") {
        `when`("성분 목록과 맵기(0)가 정상이면") {
            then("생성에 성공한다") {
                val result = FoodAvoidanceAssessmentResult(listOf(FoodAvoidanceAssessment("PORK", 80)), 0)
                result.spiciness shouldBe 0
            }
        }

        `when`("맵기가 상한(10)이면") {
            then("생성에 성공한다") {
                FoodAvoidanceAssessmentResult(emptyList(), 10).spiciness shouldBe 10
            }
        }

        `when`("맵기가 범위 밖(-1)이면") {
            then("예외가 발생한다") {
                shouldThrow<IllegalArgumentException> { FoodAvoidanceAssessmentResult(emptyList(), -1) }
            }
        }

        `when`("맵기가 범위 밖(11)이면") {
            then("예외가 발생한다") {
                shouldThrow<IllegalArgumentException> { FoodAvoidanceAssessmentResult(emptyList(), 11) }
            }
        }
    }

    given("FoodAvoidanceAssessment 생성") {
        `when`("코드·포함율이 정상이면") {
            then("생성에 성공한다") {
                FoodAvoidanceAssessment("PORK", 80).inclusionPercent shouldBe 80
            }
        }

        `when`("코드가 blank 이면") {
            then("예외가 발생한다") {
                shouldThrow<IllegalArgumentException> { FoodAvoidanceAssessment("  ", 80) }
            }
        }

        `when`("포함율이 범위 밖(-1)이면") {
            then("예외가 발생한다") {
                shouldThrow<IllegalArgumentException> { FoodAvoidanceAssessment("PORK", -1) }
            }
        }

        `when`("포함율이 범위 밖(101)이면") {
            then("예외가 발생한다") {
                shouldThrow<IllegalArgumentException> { FoodAvoidanceAssessment("PORK", 101) }
            }
        }
    }
})
