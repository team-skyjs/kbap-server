package com.kbap.common.infra.llm.translation

import com.kbap.common.domain.LanguageCode
import java.text.Normalizer

object ProcessedFoodGlossary {
    private val languages = listOf("en", "ja", "zh-Hans", "zh-Hant", "vi", "id", "th", "ru", "es")

    private class Entry(val term: String, val food: String?, val translations: Map<String, String>)

    class Term(val term: String, val fixed: String, val food: String?, val required: List<String>)

    fun isKeptIn(terms: List<Term>, output: String): Boolean {
        val folded = fold(output)
        val (foodTerms, fixedTerms) = terms.partition { it.food != null }
        return fixedTerms.all { fold(it.fixed) in folded } &&
            foodTerms.groupBy { it.food }.values.all { group -> markerCount(folded, group.first().required) >= group.size }
    }

    private fun markerCount(folded: String, markers: List<String>): Int {
        val (excluded, counted) = markers.partition { it.startsWith("!") }
        return counted.sumOf { countOf(folded, fold(it)) } - excluded.sumOf { countOf(folded, fold(it.removePrefix("!"))) }
    }

    private fun countOf(text: String, marker: String): Int = Regex.escape(marker).toRegex().findAll(text).count()

    private val foodMarkers: Map<String, Map<String, List<String>>> =
        load("translation-glossary-foods.tsv", 1 + languages.size)
            .associate { cells -> cells[0] to languages.zip(cells.drop(1)).associate { (language, markers) -> language to markers.split('|') } }

    private val entries: List<Entry> =
        load("translation-glossary.tsv", 2 + languages.size)
            .map { cells -> Entry(cells[0], cells[1].takeUnless { it == "-" }?.also { require(it in foodMarkers) { "알 수 없는 food: $it" } }, languages.zip(cells.drop(2)).toMap()) }
            .sortedByDescending { it.term.length }

    fun termsIn(line: String, target: LanguageCode): List<Term> {
        var rest = line
        return entries.mapNotNull { entry ->
            if (entry.term !in rest) return@mapNotNull null
            rest = rest.replace(entry.term, "\u0000")
            val fixed = entry.translations[target.code] ?: return@mapNotNull null
            Term(entry.term, fixed, entry.food, entry.food?.let { foodMarkers.getValue(it).getValue(target.code) } ?: listOf(fixed))
        }
    }

    private fun load(name: String, columns: Int): List<List<String>> =
        requireNotNull(ProcessedFoodGlossary::class.java.getResourceAsStream("/processed-food/$name")) { name }
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').also { require(it.size == columns) { "$name 열 수가 $columns 가 아니다: $line" } } }

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().filterNot { it.isWhitespace() }
}
