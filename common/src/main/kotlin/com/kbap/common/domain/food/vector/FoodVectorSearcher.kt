package com.kbap.common.domain.food.vector

fun interface FoodVectorSearcher {
    fun search(embedding: FloatArray, limit: Int): List<FoodVectorMatch>
}

data class FoodVectorMatch(
    val foodId: Long,
    val score: Double,
)
