package com.kbap.api.processedfood

import com.kbap.common.domain.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class UnclearIngredientLabelsTest : BehaviorSpec({
    val dictionary = requireNotNull(javaClass.getResourceAsStream("/processed-food/unclear-ingredients.tsv")).bufferedReader(Charsets.UTF_8).readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t')[0].trim() }

    given("확인 안 된 원재료 라벨 표(KB-936)") {
        `when`("사전 용어마다 10개 언어 라벨을 찾으면") {
            then("모든 용어·언어에 라벨이 있다 — 한국어는 용어 그대로, 다른 언어는 용어와 다르다") {
                LanguageCode.entries.forEach { language ->
                    UnclearIngredientLabels.labelsOf(dictionary, language).forEach { (term, label) ->
                        if (language == LanguageCode.KO) label shouldBe term else (label != term) shouldBe true
                    }
                }
            }
        }

        `when`("언어별 열을 하나씩 짚으면") {
            then("열 순서가 맞다") {
                listOf(
                    LanguageCode.EN to "Seasoning",
                    LanguageCode.JA to "シーズニング",
                    LanguageCode.ZH_HANS to "调味粉",
                    LanguageCode.ZH_HANT to "調味粉",
                    LanguageCode.VI to "Bột gia vị",
                    LanguageCode.ID to "Bumbu tabur",
                    LanguageCode.TH to "ผงปรุงรส",
                    LanguageCode.RU to "Приправа",
                    LanguageCode.ES to "Sazonador",
                ).forEach { (language, expected) -> UnclearIngredientLabels.labelsOf(listOf("시즈닝"), language).single().label shouldBe expected }
            }
        }
    }
})
