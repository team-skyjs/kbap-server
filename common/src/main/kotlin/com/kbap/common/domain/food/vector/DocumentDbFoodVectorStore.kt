package com.kbap.common.domain.food.vector

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import java.util.Date

class DocumentDbFoodVectorStore(
    private val collection: MongoCollection<Document>,
) : FoodVectorStore {
    override fun findEmbeddingHash(foodId: Long): String? =
        collection.find(Filters.eq(FoodVectorDocuments.FOOD_ID, foodId))
            .projection(Projections.include(FoodVectorDocuments.EMBEDDING_HASH))
            .first()
            ?.getString(FoodVectorDocuments.EMBEDDING_HASH)

    override fun upsert(document: FoodVectorDocument) {
        collection.replaceOne(
            Filters.eq(FoodVectorDocuments.FOOD_ID, document.foodId),
            Document()
                .append(FoodVectorDocuments.FOOD_ID, document.foodId)
                .append(FoodVectorDocuments.NAME, document.name)
                .append(FoodVectorDocuments.LONG_DESCRIPTION, document.longDescription)
                .append(FoodVectorDocuments.IMAGE_REF, document.imageRef)
                .append(FoodVectorDocuments.EMBEDDING, document.embedding.map { it.toDouble() })
                .append(FoodVectorDocuments.EMBEDDING_HASH, document.embeddingHash)
                .append(FoodVectorDocuments.EMBEDDING_MODEL, document.embeddingModel)
                .append(FoodVectorDocuments.EMBEDDING_DIMENSION, document.embeddingDimension)
                .append(FoodVectorDocuments.INDEXED_AT, Date.from(document.indexedAt)),
            ReplaceOptions().upsert(true),
        )
    }

    override fun delete(foodId: Long) {
        collection.deleteOne(Filters.eq(FoodVectorDocuments.FOOD_ID, foodId))
    }
}
