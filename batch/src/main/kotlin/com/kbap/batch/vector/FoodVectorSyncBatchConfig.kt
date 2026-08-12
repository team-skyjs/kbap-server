package com.kbap.batch.vector

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.vector.DocumentDbFoodVectorStore
import com.kbap.common.domain.food.vector.FoodVectorProperties
import com.kbap.common.domain.food.vector.FoodVectorStore
import com.kbap.common.port.llm.TextEmbeddingClient
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration
@EnableConfigurationProperties(FoodVectorProperties::class)
@ConditionalOnProperty(prefix = "kbap.vector", name = ["enabled"], havingValue = "true")
class FoodVectorSyncBatchConfig {
    @Bean(destroyMethod = "close")
    fun foodVectorMongoClient(properties: FoodVectorProperties): MongoClient = MongoClients.create(properties.uri)

    @Bean
    fun foodVectorStore(client: MongoClient, properties: FoodVectorProperties): FoodVectorStore =
        DocumentDbFoodVectorStore(client.getDatabase(properties.database).getCollection(properties.collection))

    @Bean
    fun foodVectorSyncProcessor(
        outboxRepository: FoodVectorOutboxJpaRepository,
        foodRepository: FoodJpaRepository,
        embeddingClient: TextEmbeddingClient,
        vectorStore: FoodVectorStore,
        transactionManager: PlatformTransactionManager,
        @Value("\${kbap.llm.embedding.model:amazon.titan-embed-text-v2:0}") embeddingModel: String,
        @Value("\${kbap.llm.embedding.dimension:256}") embeddingDimension: Int,
        @Value("\${kbap.batch.food-vector.page-size:100}") pageSize: Int,
    ): FoodVectorSyncProcessor =
        FoodVectorSyncProcessor(
            outboxRepository,
            foodRepository,
            embeddingClient,
            vectorStore,
            transactionManager,
            embeddingModel,
            embeddingDimension,
            pageSize,
        )

    @Bean
    fun foodVectorSyncStep(
        jobRepository: JobRepository,
        processor: FoodVectorSyncProcessor,
    ): Step =
        StepBuilder("foodVectorSyncStep", jobRepository)
            .tasklet(
                { _, _ ->
                    val summary = processor.syncAll()
                    logger.info(
                        "음식 벡터 동기화 완료 attempted={} completed={} failed={}",
                        summary.attempted,
                        summary.completed,
                        summary.failed,
                    )
                    RepeatStatus.FINISHED
                },
                ResourcelessTransactionManager(),
            )
            .build()

    @Bean
    fun foodVectorSyncJob(
        jobRepository: JobRepository,
        foodVectorSyncStep: Step,
    ): Job =
        JobBuilder("foodVectorSyncJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(foodVectorSyncStep)
            .build()

    private companion object {
        val logger = LoggerFactory.getLogger(FoodVectorSyncBatchConfig::class.java)
    }
}
