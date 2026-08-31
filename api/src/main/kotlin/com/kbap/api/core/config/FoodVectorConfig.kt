package com.kbap.api.core.config

import com.kbap.common.domain.food.vector.FoodVectorProperties
import com.kbap.common.domain.food.vector.FoodVectorSearcher
import com.kbap.common.domain.food.vector.S3VectorsFoodVectorSearcher
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3vectors.S3VectorsClient

@Configuration
@EnableConfigurationProperties(FoodVectorProperties::class)
@ConditionalOnProperty(prefix = "kbap.vector", name = ["enabled"], havingValue = "true")
class FoodVectorConfig {
    @Bean(destroyMethod = "close")
    fun foodVectorsClient(properties: FoodVectorProperties): S3VectorsClient =
        S3VectorsClient.builder().region(Region.of(properties.region)).build()

    @Bean
    fun foodVectorSearcher(client: S3VectorsClient, properties: FoodVectorProperties): FoodVectorSearcher =
        S3VectorsFoodVectorSearcher(client, properties.bucket, properties.index)
}
