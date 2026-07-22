package com.kbap.app.batch.content

import com.kbap.domain.food.FoodContentBatchService
import com.kbap.domain.food.model.Food
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.RunIdIncrementer
import org.springframework.batch.core.listener.SkipListener
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager

@Configuration
@Import(FoodContentBatchService::class)
class FoodContentBatchConfig {
    private val logger = LoggerFactory.getLogger(FoodContentBatchConfig::class.java)

    @Bean
    fun foodContentReader(
        foodContentBatchService: FoodContentBatchService,
        @Value("\${kbap.batch.content.chunk-size:10}") pageSize: Int,
    ): IncompleteFoodItemReader = IncompleteFoodItemReader(foodContentBatchService, pageSize)

    @Bean
    fun foodContentProcessor(foodContentBatchService: FoodContentBatchService): FoodContentItemProcessor =
        FoodContentItemProcessor(foodContentBatchService)

    @Bean
    fun foodContentWriter(foodContentBatchService: FoodContentBatchService): ItemWriter<Food> =
        ItemWriter { chunk ->
            chunk.items.forEach { foodContentBatchService.completeContent(it) }
        }

    @Bean
    fun foodContentStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        foodContentReader: IncompleteFoodItemReader,
        foodContentProcessor: FoodContentItemProcessor,
        foodContentWriter: ItemWriter<Food>,
        @Value("\${kbap.batch.content.chunk-size:10}") chunkSize: Int,
    ): Step =
        StepBuilder("foodContentStep", jobRepository)
            .chunk<Food, Food>(chunkSize)
            .transactionManager(transactionManager)
            .reader(foodContentReader)
            .processor(foodContentProcessor)
            .writer(foodContentWriter)
            .faultTolerant()
            .skip(Exception::class.java)
            .skipLimit(Long.MAX_VALUE)
            .skipListener(skipLogging())
            .build()

    @Bean
    fun foodContentJob(jobRepository: JobRepository, foodContentStep: Step): Job =
        JobBuilder("foodContentJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(foodContentStep)
            .build()

    private fun skipLogging(): SkipListener<Food, Food> =
        object : SkipListener<Food, Food> {
            override fun onSkipInProcess(item: Food, t: Throwable) {
                logger.warn("음식 콘텐츠 처리 실패 — 건너뜀 foodId={} message={}", item.id, t.message, t)
            }

            override fun onSkipInWrite(item: Food, t: Throwable) {
                logger.warn("음식 콘텐츠 저장 실패 — 건너뜀 foodId={} message={}", item.id, t.message, t)
            }
        }
}
