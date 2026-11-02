package com.kbap.api.processedfood

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class ProcessedFoodCvsCatalog {
    val reportNos: Set<String> = ClassPathResource(PRODUCTS).inputStream.bufferedReader(Charsets.UTF_8).readLines()
        .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    val seedLines: List<SeedLine> = ClassPathResource(SEED_LINES).inputStream.use { jacksonObjectMapper().readValue<Catalog>(it) }
        .lines.sortedBy { it.rank }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Catalog(val lines: List<SeedLine> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeedLine(val rank: Int, val line: String, val reportNos: List<String> = emptyList(), val representative: String? = null)

    private companion object {
        const val PRODUCTS = "processed-food/cvs-products.txt"
        const val SEED_LINES = "processed-food/cvs-seed-lines.json"
    }
}
