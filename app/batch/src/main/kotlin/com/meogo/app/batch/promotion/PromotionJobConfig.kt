package com.meogo.app.batch.promotion

import com.meogo.core.food.FoodRepository
import com.meogo.core.research.candidate.FoodCandidateRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PromotionJobConfig {

    @Bean
    fun foodPromotionJob(
        foodCandidateRepository: FoodCandidateRepository,
        foodRepository: FoodRepository,
        @Value("\${meogo.promotion.chunk-size:100}") chunkSize: Int,
    ): FoodPromotionJob =
        FoodPromotionJob(
            foodCandidateRepository = foodCandidateRepository,
            foodRepository = foodRepository,
            chunkSize = chunkSize,
        )

    @Bean
    @ConditionalOnProperty(prefix = "meogo.promotion.runner", name = ["enabled"], havingValue = "true")
    fun foodPromotionJobRunner(job: FoodPromotionJob): FoodPromotionJobRunner = FoodPromotionJobRunner(job)
}
