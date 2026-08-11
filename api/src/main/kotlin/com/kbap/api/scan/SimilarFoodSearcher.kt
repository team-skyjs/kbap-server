package com.kbap.api.scan

fun interface SimilarFoodSearcher {
    fun search(embedding: FloatArray, limit: Int): List<SimilarFoodDocument>
}

data class SimilarFoodDocument(
    val foodId: Long,
    val score: Double,
)
