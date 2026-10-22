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

    fun boostedProducts(text: String, tokens: List<String>): List<Boost> {
        val textKey = keyOf(text)
        val tokenKeys = tokens.map(::keyOf).toSet()
        val hits = products.mapNotNull { product ->
            val nameKey = keyOf(product.name)
            val matched = (product.aliases.map(::keyOf).filter { alias -> alias in tokenKeys || (!tokenOnly(alias) && alias in textKey) } +
                listOf(nameKey).filter { it in tokenKeys || it in textKey }).distinct()
            matched.maxByOrNull { it.length }?.takeUnless { otherMakerShown(product.maker, textKey) }?.let { Hit(product, matched, it) }
        }
        return hits
            .filterNot { hit -> hits.any { other -> other.longest.length > hit.longest.length && hit.matched.any { it in other.longest } } }
            .sortedWith(compareBy<Hit>({ -it.longest.length }, { it.product.priority }))
            .map { Boost(it.product, it.longest.codePointCount(0, it.longest.length)) }
    }

    private fun tokenOnly(alias: String): Boolean {
        val length = alias.codePointCount(0, alias.length)
        return length <= SHORT_ALIAS || (alias.endsWith(FLAVOR_SUFFIX) && length <= SHORT_FLAVOR)
    }

    private fun otherMakerShown(maker: String, textKey: String): Boolean {
        val ownInText = makers[maker].orEmpty().filter { it in textKey }
        return makers.any { (name, aliases) -> name != maker && aliases.any { alias -> alias in textKey && ownInText.none { alias in it } } }
    }

    private fun keyOf(value: String): String = ProcessedFoodSnapshot.normalizeName(Normalizer.normalize(value, Normalizer.Form.NFKC)).lowercase()

    data class Boost(val product: FeaturedProduct, val matchLength: Int)

    private data class Hit(val product: FeaturedProduct, val matched: List<String>, val longest: String)

    data class FeaturedProduct(val name: String, val maker: String, val aliases: List<String>, val priority: Int)

    data class FeaturedMaker(val name: String, val aliases: List<String>)

    private data class Catalog(val products: List<FeaturedProduct>, val makers: List<FeaturedMaker>)

    companion object {
        const val RESOURCE = "processed-food/featured-products.json"
        private const val SHORT_ALIAS = 2
        private const val FLAVOR_SUFFIX = "맛"
        private const val SHORT_FLAVOR = 4
    }
}
