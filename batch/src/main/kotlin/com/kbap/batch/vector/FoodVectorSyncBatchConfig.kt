package com.kbap.batch.vector

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.vector.FoodVectorProperties
import com.kbap.common.domain.food.vector.FoodVectorStore
import com.kbap.common.domain.food.vector.S3VectorsFoodVectorStore
import com.kbap.common.port.llm.TextEmbeddingClient
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.RunIdIncrementer
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.retry.RetryPolicy
import org.springframework.transaction.PlatformTransactionManager
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(FoodVectorProperties::class)
@ConditionalOnExpression("\${kbap.vector.enabled:false} and \${kbap.llm.embedding.enabled:false}")
class FoodVectorSyncBatchConfig {
    @Bean(destroyMethod = "close")
    fun foodVectorsClient(properties: FoodVectorProperties): S3VectorsClient =
        S3VectorsClient.builder().region(Region.of(properties.region)).build()

    @Bean
    fun foodVectorStore(client: S3VectorsClient, properties: FoodVectorProperties): FoodVectorStore =
        S3VectorsFoodVectorStore(client, properties.bucket, properties.index)

    @Bean
    fun foodVectorOutboxItemReader(
        outboxRepository: FoodVectorOutboxJpaRepository,
        @Value("\${kbap.batch.food-vector.page-size:100}") pageSize: Int,
    ): FoodVectorOutboxItemReader = FoodVectorOutboxItemReader(outboxRepository, pageSize)

    @Bean
    fun foodVectorSyncItemProcessor(
        foodRepository: FoodJpaRepository,
        outboxRepository: FoodVectorOutboxJpaRepository,
        embeddingClient: TextEmbeddingClient,
        vectorStore: FoodVectorStore,
        @Value("\${kbap.llm.embedding.model}") embeddingModel: String,
        @Value("\${kbap.llm.embedding.dimension}") embeddingDimension: Int,
    ): FoodVectorSyncItemProcessor =
        FoodVectorSyncItemProcessor(
            foodRepository,
            outboxRepository,
            embeddingClient,
            vectorStore,
            embeddingModel,
            embeddingDimension,
        )

    @Bean
    fun foodVectorSyncResultWriter(
        outboxRepository: FoodVectorOutboxJpaRepository,
    ): FoodVectorSyncResultWriter = FoodVectorSyncResultWriter(outboxRepository)

    @Bean
    fun foodVectorOutboxSkipListener(
        outboxRepository: FoodVectorOutboxJpaRepository,
    ): FoodVectorOutboxSkipListener = FoodVectorOutboxSkipListener(outboxRepository)

    @Bean
    fun foodVectorSyncRetryPolicy(
        @Value("\${kbap.batch.food-vector.max-retries:2}") maxRetries: Long,
        @Value("\${kbap.batch.food-vector.retry-delay:1s}") retryDelay: Duration,
    ): RetryPolicy =
        RetryPolicy.builder()
            .maxRetries(maxRetries)
            .delay(retryDelay)
            .multiplier(2.0)
            .jitter(retryDelay.dividedBy(5))
            .includes(setOf(Exception::class.java))
            .build()

    @Bean
    fun foodVectorSyncStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        reader: FoodVectorOutboxItemReader,
        processor: FoodVectorSyncItemProcessor,
        writer: FoodVectorSyncResultWriter,
        skipListener: FoodVectorOutboxSkipListener,
        foodVectorSyncRetryPolicy: RetryPolicy,
        @Value("\${kbap.batch.food-vector.page-size:100}") pageSize: Int,
        @Value("\${kbap.batch.food-vector.skip-limit:50}") skipLimit: Long,
    ): Step =
        StepBuilder("foodVectorSyncStep", jobRepository)
            .chunk<FoodVectorOutbox, FoodVectorOutbox>(pageSize)
            .transactionManager(transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .faultTolerant()
            .retryPolicy(foodVectorSyncRetryPolicy)
            .skipLimit(skipLimit)
            .skip(Exception::class.java)
            .skipListener(skipListener)
            .listener(
                object : StepExecutionListener {
                    override fun afterStep(stepExecution: StepExecution): ExitStatus? {
                        logger.info(
                            "음식 벡터 동기화 완료 read={} write={} skipped={} rollback={}",
                            stepExecution.readCount,
                            stepExecution.writeCount,
                            stepExecution.skipCount,
                            stepExecution.rollbackCount,
                        )
                        return null
                    }
                },
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
