package com.kbap.common.domain.food.vector

import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.PutInputVector
import software.amazon.awssdk.services.s3vectors.model.VectorData

class S3VectorsFoodVectorStore(
    private val client: S3VectorsClient,
    private val bucket: String,
    private val index: String,
) : FoodVectorStore {
    override fun findEmbeddingHash(foodId: Long): String? =
        client.getVectors {
            it.vectorBucketName(bucket)
                .indexName(index)
                .keys(foodId.toString())
                .returnMetadata(true)
                .returnData(false)
        }.vectors()
            .firstOrNull()
            ?.metadata()
            ?.asMap()
            ?.get(FoodVectorDocuments.EMBEDDING_HASH)
            ?.asString()

    override fun upsert(document: FoodVectorDocument) {
        val metadata = buildMap {
            put(FoodVectorDocuments.FOOD_ID, Document.fromNumber(document.foodId))
            put(FoodVectorDocuments.NAME, Document.fromString(document.name))
            put(FoodVectorDocuments.LONG_DESCRIPTION, Document.fromString(document.longDescription))
            put(FoodVectorDocuments.EMBEDDING_HASH, Document.fromString(document.embeddingHash))
            put(FoodVectorDocuments.EMBEDDING_MODEL, Document.fromString(document.embeddingModel))
            put(FoodVectorDocuments.EMBEDDING_DIMENSION, Document.fromNumber(document.embeddingDimension))
            put(FoodVectorDocuments.INDEXED_AT, Document.fromString(document.indexedAt.toString()))
        }
        val vector = PutInputVector.builder()
            .key(document.foodId.toString())
            .data(VectorData.fromFloat32(document.embedding.toList()))
            .metadata(Document.fromMap(metadata))
            .build()
        client.putVectors { it.vectorBucketName(bucket).indexName(index).vectors(vector) }
    }

    override fun delete(foodId: Long) {
        client.deleteVectors { it.vectorBucketName(bucket).indexName(index).keys(foodId.toString()) }
    }
}
