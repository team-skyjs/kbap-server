package com.kbap.common.domain.food.vector

import com.mongodb.client.MongoCollection
import org.bson.Document

class DocumentDbFoodVectorSearcher(
    private val collection: MongoCollection<Document>,
) : FoodVectorSearcher {
    override fun search(embedding: FloatArray, limit: Int): List<FoodVectorMatch> {
        val pipeline = listOf(
            Document(
                "\$search",
                Document(
                    "vectorSearch",
                    Document()
                        .append("vector", embedding.map { it.toDouble() })
                        .append("path", FoodVectorDocuments.EMBEDDING)
                        .append("similarity", "cosine")
                        .append("k", limit),
                ),
            ),
            Document(
                "\$project",
                Document()
                    .append(FoodVectorDocuments.FOOD_ID, 1)
                    .append(FoodVectorDocuments.SCORE, Document("\$meta", "searchScore")),
            ),
        )
        return collection.aggregate(pipeline).mapNotNull { document ->
            val foodId = (document[FoodVectorDocuments.FOOD_ID] as? Number)?.toLong() ?: return@mapNotNull null
            FoodVectorMatch(
                foodId = foodId,
                score = (document[FoodVectorDocuments.SCORE] as? Number)?.toDouble() ?: 0.0,
            )
        }.toList()
    }
}
