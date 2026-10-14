package com.kbap.api.scan

import com.kbap.api.food.FoodService
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.vector.FoodVectorSearcher
import com.kbap.common.port.llm.TextEmbeddingClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.Executors

@Service
class SimilarFoodService(
    private val embeddingClient: TextEmbeddingClient?,
    private val searcher: FoodVectorSearcher?,
    private val foodService: FoodService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolveSimilarFoods(koreanNames: List<String>): Map<String, Food> {
        val embeddingClient = embeddingClient ?: return emptyMap()
        val searcher = searcher ?: return emptyMap()
        val names = koreanNames.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return emptyMap()

        return try {
            val embeddings = embeddingClient.embed(names)
            val foodIdByName = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                names.zip(embeddings).map { (name, embedding) ->
                    executor.submit<Pair<String, Long>?> {
                        searcher.search(embedding, limit = 1).firstOrNull()
                            ?.takeIf { it.score >= SIMILARITY_THRESHOLD }
                            ?.let { name to it.foodId }
                    }
                }.mapNotNull { it.get() }.toMap()
            }
            if (foodIdByName.isEmpty()) return emptyMap()

            val foodsById = foodService.getReadyFoodsByIds(foodIdByName.values.distinct()).associateBy { it.id }
            foodIdByName.mapNotNull { (name, foodId) -> foodsById[foodId]?.let { name to it } }.toMap()
        } catch (e: Exception) {
            log.warn("유사 음식 검색 실패 — 해당 항목들은 유사 대체 없이 응답합니다", e)
            emptyMap()
        }
    }

    companion object {
        private const val SIMILARITY_THRESHOLD = 0.6
    }
}
