package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot

object ProcessedFoodSearchRanker {
    private const val EXACT = 0
    private const val PREFIX = 1
    private const val CONTAINS = 2
    private const val MAKER_ONLY = 3

    fun tokensOf(query: String): List<String> =
        query.trim().split(Regex("""\s+""")).map(ProcessedFoodSnapshot::normalizeName).filter { it.isNotBlank() }.distinct()

    fun rank(products: Collection<ProcessedFoodProduct>, tokens: List<String>): List<ProcessedFoodProduct> {
        val scored = products.mapNotNull { product -> scoreOf(product, tokens)?.let { product to it } }
        val nameMatched = scored.any { it.second.grade != MAKER_ONLY }
        return scored.filter { !nameMatched || it.second.grade != MAKER_ONLY }
            .sortedWith(compareBy({ it.second.grade }, { -it.second.coverage }, { it.first.normalizedName.length }, { it.first.id }))
            .map { it.first }
    }

    private fun scoreOf(product: ProcessedFoodProduct, tokens: List<String>): ProcessedFoodSearchScore? {
        val name = product.normalizedName
        val maker = product.displayMaker()?.let(ProcessedFoodSnapshot::normalizeName).orEmpty()
        val nameTokens = tokens.filter { it in name }
        val makerTokens = tokens.filter { it !in name && maker.isNotEmpty() && it in maker }
        if (nameTokens.isEmpty() && makerTokens.isEmpty()) return null
        val nameQuery = nameTokens.joinToString("")
        val grade = when {
            nameTokens.isEmpty() -> MAKER_ONLY
            name == nameQuery -> EXACT
            name.startsWith(nameQuery) -> PREFIX
            else -> CONTAINS
        }
        return ProcessedFoodSearchScore(grade, nameTokens.size + makerTokens.size)
    }
}

data class ProcessedFoodSearchScore(val grade: Int, val coverage: Int)
