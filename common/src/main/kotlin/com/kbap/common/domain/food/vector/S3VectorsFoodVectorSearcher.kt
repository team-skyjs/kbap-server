package com.kbap.common.domain.food.vector

import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.VectorData

class S3VectorsFoodVectorSearcher(
    private val client: S3VectorsClient,
    private val bucket: String,
    private val index: String,
) : FoodVectorSearcher {
    override fun search(embedding: FloatArray, limit: Int): List<FoodVectorMatch> =
        client.queryVectors {
            it.vectorBucketName(bucket)
                .indexName(index)
                .queryVector(VectorData.fromFloat32(embedding.toList()))
                .topK(limit)
                .returnDistance(true)
        }.vectors().mapNotNull { match ->
            val foodId = match.key().toLongOrNull() ?: return@mapNotNull null
            FoodVectorMatch(foodId = foodId, score = 1.0 - match.distance().toDouble())
        }
}
