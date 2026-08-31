package com.kbap.batch.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.port.mq.FoodContentEventPublisher
import com.kbap.common.infra.mq.SqsFoodContentEventPublisher
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient

@Configuration
class FoodContentOutboxBatchConfig {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper::class)
    fun foodContentObjectMapper(): ObjectMapper = jacksonObjectMapper()

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun foodContentSqsClient(
        @Value("\${kbap.batch.food-content-outbox.region:ap-northeast-2}") region: String,
    ): SqsClient = SqsClient.builder().region(Region.of(region)).build()

    @Bean
    @ConditionalOnMissingBean(FoodContentEventPublisher::class)
    fun foodContentEventPublisher(
        sqsClient: SqsClient,
        objectMapper: ObjectMapper,
        @Value("\${kbap.batch.food-content-outbox.queue-url}") queueUrl: String,
    ): FoodContentEventPublisher = SqsFoodContentEventPublisher(sqsClient, objectMapper, queueUrl)

    @Bean
    fun foodContentOutboxPublisher(
        outboxRepository: FoodContentOutboxJpaRepository,
        eventPublisher: FoodContentEventPublisher,
        transactionManager: PlatformTransactionManager,
        @Value("\${kbap.batch.food-content-outbox.page-size:100}") pageSize: Int,
    ): FoodContentOutboxPublisher =
        FoodContentOutboxPublisher(outboxRepository, eventPublisher, transactionManager, pageSize)

    @Bean
    fun foodContentOutboxPublishStep(
        jobRepository: JobRepository,
        publisher: FoodContentOutboxPublisher,
    ): Step =
        StepBuilder("foodContentOutboxPublishStep", jobRepository)
            .tasklet(
                { _, _ ->
                    val summary = publisher.publishAll()
                    logger.info(
                        "음식 콘텐츠 아웃박스 발행 완료 attempted={} succeeded={} failed={}",
                        summary.attempted,
                        summary.succeeded,
                        summary.failed,
                    )
                    RepeatStatus.FINISHED
                },
                ResourcelessTransactionManager(),
            )
            .build()

    @Bean
    fun foodContentOutboxPublishJob(
        jobRepository: JobRepository,
        foodContentOutboxPublishStep: Step,
    ): Job =
        JobBuilder("foodContentOutboxPublishJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(foodContentOutboxPublishStep)
            .build()

    private companion object {
        val logger = LoggerFactory.getLogger(FoodContentOutboxBatchConfig::class.java)
    }
}
