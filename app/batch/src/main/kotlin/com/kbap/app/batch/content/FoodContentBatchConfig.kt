package com.kbap.app.batch.content

import com.kbap.core.food.FoodAvoidanceAssessmentClient
import com.kbap.domain.avoidance.AvoidanceSubstanceJpaRepository
import com.kbap.domain.food.FoodJpaRepository
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
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration
class FoodContentBatchConfig {
    private val logger = LoggerFactory.getLogger(FoodContentBatchConfig::class.java)

    @Bean
    fun foodContentReader(
        foodRepository: FoodJpaRepository,
        @Value("\${kbap.batch.content.chunk-size:10}") pageSize: Int,
    ): IncompleteFoodItemReader = IncompleteFoodItemReader(foodRepository, pageSize)

    @Bean
    fun foodContentProcessor(
        foodRepository: FoodJpaRepository,
        transactionManager: PlatformTransactionManager,
        avoidanceClient: FoodAvoidanceAssessmentClient,
        avoidanceRepository: AvoidanceSubstanceJpaRepository,
    ): FoodContentItemProcessor =
        FoodContentItemProcessor(foodRepository, transactionManager, avoidanceClient) {
            avoidanceRepository.findAll().map { it.code.name }.toSet()
        }

    @Bean
    fun foodContentWriter(foodRepository: FoodJpaRepository): ItemWriter<Food> =
        ItemWriter { chunk ->
            chunk.items.forEach {
                it.transitionToPendingReviewIfComplete()
                foodRepository.save(it)
            }
        }

    @Bean
    fun foodContentStep(
        jobRepository: JobRepository,
        foodContentReader: IncompleteFoodItemReader,
        foodContentProcessor: FoodContentItemProcessor,
        foodContentWriter: ItemWriter<Food>,
        @Value("\${kbap.batch.content.chunk-size:10}") chunkSize: Int,
    ): Step =
        StepBuilder("foodContentStep", jobRepository)
            .chunk<Food, Food>(chunkSize)
            // 청크 트랜잭션을 끈다(resourceless) — 외부 LLM 호출이 DB 커넥션을 청크 내내 물지 않게.
            // DB 쓰기는 각자 자기 트랜잭션으로 커밋한다(processor 의 REQUIRES_NEW·writer 의 save).
            .transactionManager(ResourcelessTransactionManager())
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
