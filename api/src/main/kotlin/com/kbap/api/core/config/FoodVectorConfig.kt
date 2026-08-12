package com.kbap.api.core.config

import com.kbap.common.domain.food.vector.DocumentDbFoodVectorSearcher
import com.kbap.common.domain.food.vector.FoodVectorProperties
import com.kbap.common.domain.food.vector.FoodVectorSearcher
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(FoodVectorProperties::class)
@ConditionalOnProperty(prefix = "kbap.vector", name = ["enabled"], havingValue = "true")
class FoodVectorConfig {
    @Bean(destroyMethod = "close")
    fun vectorMongoClient(properties: FoodVectorProperties): MongoClient = MongoClients.create(properties.uri)

    @Bean
    fun foodVectorSearcher(client: MongoClient, properties: FoodVectorProperties): FoodVectorSearcher =
        DocumentDbFoodVectorSearcher(client.getDatabase(properties.database).getCollection(properties.collection))
}
