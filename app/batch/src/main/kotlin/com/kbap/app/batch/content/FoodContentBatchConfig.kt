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
// 각 작업이 결과를 즉시 개별 커밋(processor→saveProgress, REQUIRES_NEW)하므로, 청크가 롤백·재스캔돼도
// 이미 된 작업은 needsX=false 로 건너뛰어 LLM 을 다시 태우지 않는다 → chunk-size 를 크게(10) 잡아도 안전하다.
// faultTolerant + skip: 한 음식 처리 실패는 그 건만 건너뛰고(INCOMPLETE 잔류·다음 실행에서 실패 작업만 재시도) 잡은 계속된다.
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
    fun foodContentProcessor(foodContentBatchService: FoodContentBatchService): FoodContentItemProcessor =
        FoodContentItemProcessor(foodContentBatchService)

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
        @Value("\${kbap.batch.content.chunk-size:10}") chunkSize: Int,
    ): Step =
        StepBuilder("foodContentStep", jobRepository)
            .chunk<Food, ProcessedFood>(chunkSize, transactionManager)
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
