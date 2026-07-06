package com.meogo.core.research.ensemble

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.research.parse.ModelScoring
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class FoodContentSelectorTest : BehaviorSpec({

    val selector = FoodContentSelector()

    val foodId = 1L

    given("두 모델이 같은 언어의 이름 번역을 모두 보유") {
        val first = ModelScoring(
            nameTranslations = mapOf(foodId to mapOf(LanguageCode.EN to "A")),
            descriptions = mapOf(foodId to LocalizedText(korean = "설명A")),
        )
        val second = ModelScoring(
            nameTranslations = mapOf(foodId to mapOf(LanguageCode.EN to "B")),
            descriptions = mapOf(foodId to LocalizedText(korean = "설명B")),
        )

        `when`("선택하면") {
            val content = selector.select(foodId, listOf(first, second))

            then("겹치는 언어는 우선순위 첫 모델 값을 채택한다") {
                content.nameTranslations shouldBe mapOf(LanguageCode.EN to "A")
            }
            then("설명 한국어도 우선순위 첫 모델 값을 채택한다") {
                content.description?.korean shouldBe "설명A"
            }
        }
    }

    given("첫 모델은 일부 언어만, 둘째 모델은 더 많은 언어를 보유") {
        val first = ModelScoring(
            nameTranslations = mapOf(
                foodId to mapOf(
                    LanguageCode.EN to "Miso Stew",
                    LanguageCode.JA to "味噌汁",
                ),
            ),
            descriptions = mapOf(
                foodId to LocalizedText(
                    korean = "첫설명",
                    translations = mapOf(LanguageCode.EN to "first-en"),
                ),
            ),
        )
        val second = ModelScoring(
            nameTranslations = mapOf(
                foodId to mapOf(
                    LanguageCode.EN to "Doenjang Jjigae",
                    LanguageCode.JA to "テンジャンチゲ",
                    LanguageCode.TH to "แกงเต้าเจี้ยว",
                    LanguageCode.ES to "Guiso de Doenjang",
                ),
            ),
            descriptions = mapOf(
                foodId to LocalizedText(
                    korean = "둘째설명",
                    translations = mapOf(
                        LanguageCode.EN to "second-en",
                        LanguageCode.RU to "second-ru",
                    ),
                ),
            ),
        )

        `when`("선택하면") {
            val content = selector.select(foodId, listOf(first, second))

            then("언어별로 병합해 첫 모델에 없는 언어는 둘째 모델 값으로 채운다") {
                content.nameTranslations shouldBe mapOf(
                    LanguageCode.EN to "Miso Stew",
                    LanguageCode.JA to "味噌汁",
                    LanguageCode.TH to "แกงเต้าเจี้ยว",
                    LanguageCode.ES to "Guiso de Doenjang",
                )
            }
            then("설명 번역도 언어별로 병합한다") {
                content.description?.korean shouldBe "첫설명"
                content.description?.translations shouldBe mapOf(
                    LanguageCode.EN to "first-en",
                    LanguageCode.RU to "second-ru",
                )
            }
        }
    }

    given("첫 모델은 그 음식 텍스트가 없고 둘째 모델이 보유") {
        val first = ModelScoring(
            nameTranslations = mapOf(2L to mapOf(LanguageCode.EN to "Other")),
        )
        val second = ModelScoring(
            nameTranslations = mapOf(foodId to mapOf(LanguageCode.JA to "ビビンバ")),
            descriptions = mapOf(foodId to LocalizedText(korean = "둘째설명")),
        )

        `when`("선택하면") {
            val content = selector.select(foodId, listOf(first, second))

            then("둘째 모델의 이름 번역을 채택한다") {
                content.nameTranslations shouldBe mapOf(LanguageCode.JA to "ビビンバ")
            }
            then("둘째 모델의 설명을 채택한다") {
                content.description?.korean shouldBe "둘째설명"
            }
        }
    }

    given("첫 모델은 이름 번역만 있고 설명은 둘째 모델만 보유") {
        val first = ModelScoring(
            nameTranslations = mapOf(foodId to mapOf(LanguageCode.EN to "A")),
            descriptions = emptyMap(),
        )
        val second = ModelScoring(
            nameTranslations = mapOf(foodId to mapOf(LanguageCode.EN to "B")),
            descriptions = mapOf(foodId to LocalizedText(korean = "설명B")),
        )

        `when`("선택하면") {
            val content = selector.select(foodId, listOf(first, second))

            then("이름 번역은 첫 모델 값을 유지한다") {
                content.nameTranslations shouldBe mapOf(LanguageCode.EN to "A")
            }
            then("설명은 보유한 둘째 모델 값으로 채운다") {
                content.description?.korean shouldBe "설명B"
            }
        }
    }

    given("모든 모델이 그 음식 텍스트를 제공하지 않음") {
        val models = listOf(
            ModelScoring(),
            ModelScoring(),
            ModelScoring(),
        )

        `when`("선택하면") {
            val content = selector.select(foodId, models)

            then("이름 번역은 빈 맵이다") {
                content.nameTranslations shouldBe emptyMap()
            }
            then("설명은 null 이다") {
                content.description.shouldBeNull()
            }
        }
    }

    given("동일 입력") {
        val models = listOf(
            ModelScoring(
                nameTranslations = mapOf(foodId to mapOf(LanguageCode.EN to "A")),
                descriptions = mapOf(foodId to LocalizedText(korean = "설명A")),
            ),
            ModelScoring(
                nameTranslations = mapOf(foodId to mapOf(LanguageCode.EN to "B", LanguageCode.JA to "B-ja")),
                descriptions = mapOf(foodId to LocalizedText(korean = "설명B")),
            ),
        )

        `when`("두 번 선택하면") {
            val first = selector.select(foodId, models)
            val second = selector.select(foodId, models)

            then("결과가 동일하다") {
                first shouldBe second
            }
        }
    }
})
