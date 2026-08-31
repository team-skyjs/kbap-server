package com.kbap.common.domain.food.vector

import java.security.MessageDigest

object FoodVectorDocuments {
    const val FOOD_ID = "foodId"
    const val NAME = "name"
    const val LONG_DESCRIPTION = "longDescription"
    const val IMAGE_REF = "imageRef"
    const val EMBEDDING = "embedding"
    const val EMBEDDING_HASH = "embeddingHash"
    const val EMBEDDING_MODEL = "embeddingModel"
    const val EMBEDDING_DIMENSION = "embeddingDimension"
    const val INDEXED_AT = "indexedAt"

    fun embeddingText(name: String, longDescription: String): String = "$name\n$longDescription"

    fun embeddingHash(embeddingModel: String, embeddingDimension: Int, embeddingText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$embeddingModel|$embeddingDimension|$embeddingText".toByteArray())
        return HASH_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private const val HASH_PREFIX = "sha256:"
}
