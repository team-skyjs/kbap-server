package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot

object ProcessedFoodSearchRanker {
    private const val EXACT = 0
    private const val PREFIX = 1
    private const val CONTAINS = 2
    private const val MAKER_ONLY = 3
    private const val MAX_TOKENS = 5

    fun tokensOf(query: String): List<String> =
        query.trim().split(Regex("""\s+""")).map(ProcessedFoodSnapshot::normalizeName).filter { it.isNotBlank() }.distinct().take(MAX_TOKENS)

    fun rawTokensOf(query: String): List<String> =
        query.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }.distinct().take(MAX_TOKENS)

    fun isExact(score: ProcessedFoodSearchScore): Boolean = score.grade == EXACT

    val ORDER: Comparator<Pair<ProcessedFoodProduct, ProcessedFoodSearchScore>> =
        compareBy({ it.second.grade }, { -it.second.coverage }, { it.second.source }, { it.second.corrected }, { it.second.nameLength }, { it.first.id })

    fun rank(products: Collection<ProcessedFoodProduct>, tokens: List<String>): List<ProcessedFoodProduct> =
        scored(products, tokens).sortedWith(ORDER).map { it.first }

    fun scored(
        products: Collection<ProcessedFoodProduct>,
        tokens: List<String>,
        corrected: Set<String> = emptySet(),
        translated: Map<Long, List<ProcessedFoodCandidateIndex.TranslatedName>> = emptyMap(),
    ): List<Pair<ProcessedFoodProduct, ProcessedFoodSearchScore>> {
        val scored = products.mapNotNull { product ->
            (listOfNotNull(scoreOf(product, tokens, corrected)) + translated[product.id].orEmpty().mapNotNull { translatedScoreOf(it, tokens) })
                .minWithOrNull(compareBy({ it.grade }, { -it.coverage }, { it.source }, { it.nameLength }))
                ?.let { product to it }
        }
        val nameMatched = scored.any { it.second.grade != MAKER_ONLY }
        return scored.filter { !nameMatched || it.second.grade != MAKER_ONLY }
    }

    private fun scoreOf(product: ProcessedFoodProduct, tokens: List<String>, corrected: Set<String>): ProcessedFoodSearchScore? {
        val name = product.normalizedName.lowercase()
        val maker = product.displayMaker()?.let(ProcessedFoodSnapshot::normalizeName)?.lowercase().orEmpty()
        val nameTokens = tokens.filter { it.lowercase() in name }
        val makerTokens = tokens.filter { it.lowercase() !in name && maker.isNotEmpty() && it.lowercase() in maker }
        if (nameTokens.isEmpty() && makerTokens.isEmpty()) return null
        val nameQuery = nameTokens.joinToString("").lowercase()
        val grade = when {
            nameTokens.isEmpty() -> MAKER_ONLY
            name == nameQuery -> EXACT
            name.startsWith(nameQuery) -> PREFIX
            else -> CONTAINS
        }
        return ProcessedFoodSearchScore(grade, nameTokens.size + makerTokens.size, nameTokens.count { it in corrected }, nameLength = name.length)
    }

    private fun translatedScoreOf(translated: ProcessedFoodCandidateIndex.TranslatedName, tokens: List<String>): ProcessedFoodSearchScore? {
        val name = translated.name
        val nameTokens = tokens.map { it.lowercase() }.filter { it in name }
        if (nameTokens.isEmpty()) return null
        val nameQuery = nameTokens.joinToString("")
        val grade = when {
            name == nameQuery -> EXACT
            name.startsWith(nameQuery) -> PREFIX
            else -> CONTAINS
        }
        return ProcessedFoodSearchScore(grade, nameTokens.size, source = translated.source, nameLength = name.length)
    }
}

data class ProcessedFoodSearchScore(
    val grade: Int,
    val coverage: Int,
    val corrected: Int = 0,
    val source: Int = ProcessedFoodCandidateIndex.ORIGINAL,
    val nameLength: Int = 0,
)
