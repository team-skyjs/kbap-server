package com.kbap.api.scan

import com.kbap.common.domain.food.vector.FoodVectorMatch
import com.kbap.common.domain.food.vector.FoodVectorSearcher
import com.kbap.common.port.llm.TextEmbeddingClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class FakeSimilarFoodSearch : TextEmbeddingClient, FoodVectorSearcher {
    private val documentsByName = mutableMapOf<String, FoodVectorMatch>()
    private val embeddedNames = mutableListOf<String>()
    private var failSearch = false

    fun program(koreanName: String, foodId: Long, score: Double) {
        documentsByName[koreanName] = FoodVectorMatch(foodId, score)
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

    override fun search(embedding: FloatArray, limit: Int): List<FoodVectorMatch> {
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
