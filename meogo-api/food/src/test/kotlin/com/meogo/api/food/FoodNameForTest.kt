package com.meogo.api.food

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodNameForTest : BehaviorSpec({
    given("Ingredient.nameFor 언어별 이름") {
        val clam = Ingredient(
            koreanName = "바지락 조개",
            names = mapOf(LanguageCode.EN to "Manila clam", LanguageCode.JA to "アサリ"),
            iconRef = null,
        )

        `when`("ko 로 요청하면") {
            then("한국어 원문을 반환한다") {
                clam.nameFor(LanguageCode.KO) shouldBe "바지락 조개"
            }
        }

        `when`("번역이 있는 언어로 요청하면") {
            then("해당 언어 번역을 반환한다") {
                clam.nameFor(LanguageCode.EN) shouldBe "Manila clam"
                clam.nameFor(LanguageCode.JA) shouldBe "アサリ"
            }
        }

        `when`("번역이 없는 언어로 요청하면") {
            then("한국어 원문으로 폴백한다") {
                clam.nameFor(LanguageCode.ES) shouldBe "바지락 조개"
            }
        }
    }

    given("Food.nameFor 언어별 이름") {
        val stew = Food(
            koreanName = "된장찌개",
            names = mapOf(LanguageCode.EN to "Doenjang Stew", LanguageCode.JA to "テンジャンチゲ"),
            imageRef = null,
            ingredients = emptyList(),
        )

        `when`("ko 로 요청하면") {
            then("한국어 원문을 반환한다") {
                stew.nameFor(LanguageCode.KO) shouldBe "된장찌개"
            }
        }

        `when`("번역이 있는 언어로 요청하면") {
            then("해당 언어 번역을 반환한다") {
                stew.nameFor(LanguageCode.EN) shouldBe "Doenjang Stew"
            }
        }

        `when`("번역이 없는 언어로 요청하면") {
            then("한국어 원문으로 폴백한다") {
                stew.nameFor(LanguageCode.ES) shouldBe "된장찌개"
            }
        }
    }
})
