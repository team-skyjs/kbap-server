package com.kbap.api.food

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.dto.FoodListingName
import com.kbap.common.domain.food.model.Food
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class FoodListingSortTest : BehaviorSpec({
    fun entry(id: Long, name: String) = FoodListingName(id, name, name, emptyMap(), LocalDateTime.MIN)

    given("고정 로케일 비교 규칙") {
        `when`("각 언어의 대표 문자를 비교하면") {
            then("10개 언어에서 지정한 정순과 역순을 따른다") {
                val samples = mapOf(
                    LanguageCode.KO to listOf("가", "나", "다"),
                    LanguageCode.EN to listOf("Apple", "Banana", "Carrot"),
                    LanguageCode.JA to listOf("あ", "い", "う"),
                    LanguageCode.ZH_HANS to listOf("阿", "八", "擦"),
                    LanguageCode.ZH_HANT to listOf("一", "二", "三"),
                    LanguageCode.VI to listOf("a", "ă", "â"),
                    LanguageCode.ID to listOf("apel", "bakso", "cumi"),
                    LanguageCode.TH to listOf("ก", "ข", "ค"),
                    LanguageCode.RU to listOf("а", "б", "в"),
                    LanguageCode.ES to listOf("n", "ñ", "o"),
                )
                samples.forEach { (lang, names) ->
                    val rows = names.mapIndexed { index, name -> entry(index + 1L, name) }.reversed()
                    FoodListingSort.NAME_ASC.order(rows, lang) shouldBe listOf(1L, 2L, 3L)
                    FoodListingSort.NAME_DESC.order(rows, lang) shouldBe listOf(3L, 2L, 1L)
                }
            }
        }
        `when`("대소문자와 숫자 표기가 비교상 같으면") {
            then("foodId로 동률을 해소하고 악센트는 구분한다") {
                val rows = listOf(entry(4, "Menu 10"), entry(3, "menu 02"), entry(2, "MENU 2"), entry(1, "Menu 2"))
                FoodListingSort.NAME_ASC.order(rows, LanguageCode.EN) shouldBe listOf(1L, 2L, 3L, 4L)
                FoodListingSort.NAME_DESC.order(rows, LanguageCode.EN) shouldBe listOf(4L, 3L, 2L, 1L)
                FoodListingSort.NAME_ASC.order(listOf(entry(1, "é"), entry(2, "e")), LanguageCode.EN) shouldBe listOf(2L, 1L)
            }
        }
        `when`("공백·기호·정규화가 다른 이름이면") {
            then("기호를 버리지 않고 정규화상 같은 악센트는 동률로 처리한다") {
                FoodListingSort.NAME_ASC.order(listOf(entry(1, "ab"), entry(2, "a-b"), entry(3, "a b")), LanguageCode.EN) shouldBe listOf(3L, 2L, 1L)
                FoodListingSort.NAME_ASC.order(listOf(entry(2, "é"), entry(1, "e\u0301")), LanguageCode.EN) shouldBe listOf(1L, 2L)
            }
        }
        `when`("번역이 누락되거나 공백이면") {
            then("엔티티 응답과 정렬 projection의 표시명이 모든 언어에서 일치한다") {
                listOf(emptyMap(), mapOf("en" to " ", "ja" to "料理", "ko" to "사용하지 않음", "xx" to "미지원")).forEach { translations ->
                    listOf("한국어 표시명", "  ").forEach { display ->
                        val row = FoodListingName(1, "원문 이름", display, translations, LocalDateTime.MIN)
                        val food = Food(koreanName = row.koreanName, displayName = display, nameTranslations = translations)
                        LanguageCode.entries.forEach { lang -> row.name(lang) shouldBe food.displayName(lang) }
                    }
                }
            }
        }
    }
})
