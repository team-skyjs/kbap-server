package com.kbap.api.processedfood

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.text.Normalizer

@Component
class ProcessedFoodFeaturedCatalog {
    private val catalog: Catalog = ClassPathResource(RESOURCE).inputStream.use { jacksonObjectMapper().readValue(it) }
    private val products: List<FeaturedProduct> = catalog.products.sortedBy { it.priority }
    private val makers: Map<String, List<String>> = catalog.makers.associate { maker -> maker.name to maker.aliases.map(::keyOf) }

    fun boostedProducts(text: String, tokens: List<String>): List<FeaturedProduct> {
        val textKey = keyOf(text)
        val tokenKeys = tokens.map(::keyOf).toSet()
        return products.filter { product ->
            product.aliases.map(::keyOf).any { it in tokenKeys || it in textKey } && !otherMakerShown(product.maker, textKey)
        }
    }

    private fun otherMakerShown(maker: String, textKey: String): Boolean {
        val ownInText = makers[maker].orEmpty().filter { it in textKey }
        return makers.any { (name, aliases) -> name != maker && aliases.any { alias -> alias in textKey && ownInText.none { alias in it } } }
    }

    private fun keyOf(value: String): String = ProcessedFoodSnapshot.normalizeName(Normalizer.normalize(value, Normalizer.Form.NFKC)).lowercase()

    data class FeaturedProduct(val name: String, val maker: String, val aliases: List<String>, val priority: Int)

    data class FeaturedMaker(val name: String, val aliases: List<String>)

    private data class Catalog(val products: List<FeaturedProduct>, val makers: List<FeaturedMaker>)

    companion object {
        const val RESOURCE = "processed-food/featured-products.json"
    }
}
