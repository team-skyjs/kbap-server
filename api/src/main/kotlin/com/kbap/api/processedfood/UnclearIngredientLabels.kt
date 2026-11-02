package com.kbap.api.processedfood

import com.kbap.common.domain.LanguageCode
import io.swagger.v3.oas.annotations.media.Schema

object UnclearIngredientLabels {
    private val columns = listOf("ko", "en", "ja", "zh-Hans", "zh-Hant", "vi", "id", "th", "ru", "es")

    private val labels: Map<String, Map<String, String>> =
        requireNotNull(UnclearIngredientLabels::class.java.getResourceAsStream("/processed-food/unclear-ingredient-labels.tsv")) { "unclear-ingredient-labels.tsv" }
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .associate { cells -> cells[0].trim() to columns.zip(cells.drop(1)).associate { (lang, label) -> lang to label.trim() } }

    fun labelsOf(terms: List<String>, language: LanguageCode): List<UnclearIngredientLabel> =
        terms.map { term -> UnclearIngredientLabel(term, labels[term]?.get(language.code)?.takeIf { it.isNotEmpty() } ?: term) }
}

@Schema(description = "확인 안 된 원재료 사전 용어와 그 사용자 언어 라벨")
data class UnclearIngredientLabel(
    @field:Schema(description = "서버 사전 용어(한국어 원문)", example = "복합조미식품")
    val term: String,
    @field:Schema(description = "요청 lang 라벨 — 라벨이 없으면 용어 그대로", example = "Compound seasoning")
    val label: String,
)
