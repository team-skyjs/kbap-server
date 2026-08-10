package com.kbap.api.scan

import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.food.model.Food
import com.kbap.common.port.llm.TextEmbeddingClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SimilarFoodResolver(
    // nullable 주입 = 선택 의존 — 임베딩·검색 빈이 없는 환경(local 등)에서는 유사 폴백이 no-op 이다.
    private val embeddingClient: TextEmbeddingClient?,
    private val searcher: SimilarFoodSearcher?,
    private val foodService: FoodService,
    @Value("\${kbap.vector.similarity-threshold:0.0}") private val similarityThreshold: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 유사 대체는 부가 정보 — 외부 장애 시 빈 결과로 스캔을 살린다(부분 성공).
    fun resolveSimilarFoods(koreanNames: List<String>): Map<String, Food> {
        val embeddingClient = embeddingClient ?: return emptyMap()
        val searcher = searcher ?: return emptyMap()
        val names = koreanNames.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return emptyMap()

        return try {
            val embeddings = embeddingClient.embed(names)
            val foodIdByName = names.zip(embeddings).mapNotNull { (name, embedding) ->
                searcher.search(embedding, limit = 1).firstOrNull()
                    ?.takeIf { it.score >= similarityThreshold }
                    ?.let { name to it.foodId }
            }.toMap()
            if (foodIdByName.isEmpty()) return emptyMap()

            val foodsById = foodService.getReadyFoodsByIds(foodIdByName.values.distinct()).associateBy { it.id }
            foodIdByName.mapNotNull { (name, foodId) -> foodsById[foodId]?.let { name to it } }.toMap()
        } catch (e: Exception) {
            log.warn("유사 음식 검색 실패 — 해당 항목들은 유사 대체 없이 응답합니다", e)
            emptyMap()
        }
    }
}
