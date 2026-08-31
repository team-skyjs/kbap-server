package com.kbap.common.domain.food.vector

import java.time.Instant

interface FoodVectorStore {
    fun findEmbeddingHash(foodId: Long): String?

    fun upsert(document: FoodVectorDocument)

    fun delete(foodId: Long)
}

class FoodVectorDocument(
    val foodId: Long,
    val name: String,
    val longDescription: String,
    val imageRef: String?,
    val embedding: FloatArray,
    val embeddingHash: String,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val indexedAt: Instant,
)
