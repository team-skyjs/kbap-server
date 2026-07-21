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

// 콘텐츠 파이프라인(KB-182) — INCOMPLETE 음식을 chunk-oriented Step 으로 소진한다.
// commit-interval=1: 음식 1건 = 트랜잭션 1개. skip 시 형제 음식을 재처리(중복 LLM 호출)하지 않게 한다.
// faultTolerant + skip: 한 음식 처리 실패는 그 건만 건너뛰고(INCOMPLETE 잔류·다음 실행 재시도) 잡은 계속된다.
// FoodContentBatchService(:domain:food 창구, internal constructor)를 @Import 로 조립한다.
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
    fun foodContentProcessor(): FoodContentItemProcessor = FoodContentItemProcessor()

    @Bean
    fun foodContentWriter(foodContentBatchService: FoodContentBatchService): ItemWriter<ProcessedFood> =
        ItemWriter { chunk ->
            chunk.items.forEach { foodContentBatchService.completeContent(it.food, it.hasAvoidanceMapping) }
        }

    @Bean
    fun foodContentStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        foodContentReader: IncompleteFoodItemReader,
        foodContentProcessor: FoodContentItemProcessor,
        foodContentWriter: ItemWriter<ProcessedFood>,
    ): Step =
        StepBuilder("foodContentStep", jobRepository)
            .chunk<Food, ProcessedFood>(1, transactionManager)
            .reader(foodContentReader)
            .processor(foodContentProcessor)
            .writer(foodContentWriter)
            .faultTolerant()
            .skip(Exception::class.java)
            .skipLimit(Int.MAX_VALUE)
            .listener(skipLogging())
            .build()

    @Bean
    fun foodContentJob(jobRepository: JobRepository, foodContentStep: Step): Job =
        JobBuilder("foodContentJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(foodContentStep)
            .build()

    private fun skipLogging(): SkipListener<Food, ProcessedFood> =
        object : SkipListener<Food, ProcessedFood> {
            override fun onSkipInProcess(item: Food, t: Throwable) {
                logger.warn("음식 콘텐츠 처리 실패 — 건너뜀 foodId={} message={}", item.id, t.message, t)
            }

            override fun onSkipInWrite(item: ProcessedFood, t: Throwable) {
                logger.warn("음식 콘텐츠 저장 실패 — 건너뜀 foodId={} message={}", item.food.id, t.message, t)
            }
        }
}
