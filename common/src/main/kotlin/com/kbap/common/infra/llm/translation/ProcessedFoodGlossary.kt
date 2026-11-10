package com.kbap.common.infra.llm.translation

import com.kbap.common.domain.LanguageCode
import java.text.Normalizer

object ProcessedFoodGlossary {
    private val columns = listOf("ko", "en", "ja", "zh-Hans", "zh-Hant", "vi", "id", "th", "ru", "es")

    private val entries: List<Pair<String, Map<String, String>>> =
        requireNotNull(ProcessedFoodGlossary::class.java.getResourceAsStream("/processed-food/translation-glossary.tsv")) { "translation-glossary.tsv" }
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').also { require(it.size == columns.size) { "translation-glossary.tsv 열 수가 ${columns.size} 가 아니다: $line" } } }
            .map { cells -> cells[0] to columns.drop(1).zip(cells.drop(1)).toMap() }
            .sortedByDescending { it.first.length }

    fun termsIn(line: String, target: LanguageCode): List<Pair<String, String>> {
        var rest = line
        return entries.mapNotNull { (term, translations) ->
            if (term !in rest) return@mapNotNull null
            rest = rest.replace(term, "\u0000")
            translations[target.code]?.let { term to it }
        }
    }

    fun contains(output: String, fixed: String): Boolean = fold(fixed) in fold(output)

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().filterNot { it.isWhitespace() }
}
