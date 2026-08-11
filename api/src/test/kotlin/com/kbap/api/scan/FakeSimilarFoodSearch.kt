package com.kbap.api.scan

import com.kbap.common.port.llm.TextEmbeddingClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class FakeSimilarFoodSearch : TextEmbeddingClient, SimilarFoodSearcher {
    private val documentsByName = mutableMapOf<String, SimilarFoodDocument>()
    private val embeddedNames = mutableListOf<String>()
    private var failSearch = false

    fun program(koreanName: String, foodId: Long, score: Double) {
        documentsByName[koreanName] = SimilarFoodDocument(foodId, score)
    }

    fun failSearch() {
        failSearch = true
    }

    fun reset() {
        documentsByName.clear()
        embeddedNames.clear()
        failSearch = false
    }

    override fun embed(texts: List<String>): List<FloatArray> =
        texts.map { text ->
            embeddedNames.add(text)
            floatArrayOf((embeddedNames.size - 1).toFloat())
        }

    override fun search(embedding: FloatArray, limit: Int): List<SimilarFoodDocument> {
        if (failSearch) throw RuntimeException("vector 검색 실패(테스트)")
        val name = embeddedNames[embedding[0].toInt()]
        return listOfNotNull(documentsByName[name])
    }
}

@Configuration
class FakeSimilarFoodSearchConfig {
    @Bean
    fun fakeSimilarFoodSearch(): FakeSimilarFoodSearch = FakeSimilarFoodSearch()
}
