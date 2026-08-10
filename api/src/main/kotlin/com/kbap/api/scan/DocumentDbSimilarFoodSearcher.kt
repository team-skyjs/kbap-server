package com.kbap.api.scan

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import org.bson.Document
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// DocumentDB 벡터 검색 문법($search.vectorSearch)은 MongoDB Atlas($vectorSearch)와 다르다 — contracts/vector-food-document.md.
class DocumentDbSimilarFoodSearcher(
    private val collection: MongoCollection<Document>,
) : SimilarFoodSearcher {
    override fun search(embedding: FloatArray, limit: Int): List<SimilarFoodDocument> {
        val pipeline = listOf(
            Document(
                "\$search",
                Document(
                    "vectorSearch",
                    Document()
                        .append("vector", embedding.map { it.toDouble() })
                        .append("path", "embedding")
                        .append("similarity", "cosine")
                        .append("k", limit),
                ),
            ),
            Document(
                "\$project",
                Document()
                    .append("foodId", 1)
                    .append("score", Document("\$meta", "searchScore")),
            ),
        )
        return collection.aggregate(pipeline).mapNotNull { document ->
            val foodId = (document["foodId"] as? Number)?.toLong() ?: return@mapNotNull null
            SimilarFoodDocument(
                foodId = foodId,
                score = (document["score"] as? Number)?.toDouble() ?: 0.0,
            )
        }.toList()
    }
}

@ConfigurationProperties("kbap.vector")
data class VectorSearchProperties(
    val enabled: Boolean = false,
    val uri: String = "",
    val database: String = "kbap",
    val collection: String = "foods",
)

@Configuration
@EnableConfigurationProperties(VectorSearchProperties::class)
@ConditionalOnProperty(prefix = "kbap.vector", name = ["enabled"], havingValue = "true")
class VectorSearchConfiguration {
    @Bean(destroyMethod = "close")
    fun vectorMongoClient(properties: VectorSearchProperties): MongoClient = MongoClients.create(properties.uri)

    @Bean
    fun similarFoodSearcher(client: MongoClient, properties: VectorSearchProperties): SimilarFoodSearcher =
        DocumentDbSimilarFoodSearcher(client.getDatabase(properties.database).getCollection(properties.collection))
}
