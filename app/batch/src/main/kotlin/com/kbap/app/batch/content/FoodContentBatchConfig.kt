package com.kbap.app.batch.content

import com.kbap.common.core.food.FoodAvoidanceAssessmentClient
import com.kbap.common.core.food.FoodDescriptionClient
import com.kbap.common.core.food.FoodNameTranslationClient
import com.kbap.common.domain.avoidance.AvoidanceSubstanceJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
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
import org.springframework.beans.factory.ObjectProvider
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
        descriptionClientProvider: ObjectProvider<FoodDescriptionClient>,
        nameTranslationClientProvider: ObjectProvider<FoodNameTranslationClient>,
    ): FoodContentItemProcessor =
        FoodContentItemProcessor(
            foodRepository,
            transactionManager,
            avoidanceClient,
            descriptionClientProvider.getIfAvailable(),
            nameTranslationClientProvider.getIfAvailable(),
        ) {
            avoidanceRepository.findAll().map { it.code.name }.toSet()
        }

    @Bean
    fun foodContentWriter(foodRepository: FoodJpaRepository): ItemWriter<Food> =
        FoodContentItemWriter(foodRepository)

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
            .transactionManager(ResourcelessTransactionManager())
            .reader(foodContentReader)
            .processor(foodContentProcessor)
            .writer(foodContentWriter)
            .faultTolerant()
            .skipPolicy { t, _ -> t !is FoodContentClientNotConfiguredException }
            .skipListener(skipLogging())
            .build()

    @Bean
    fun foodContentJob(
        jobRepository: JobRepository, 
        foodContentStep: Step
    ): Job =
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
