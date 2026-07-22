package com.kbap.app.batch.content

import com.kbap.domain.food.FoodContentBatchService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(FoodContentBatchService::class)
class ContentJobConfig {

    @Bean
    fun foodContentJob(
        foodContentBatchService: FoodContentBatchService,
        @Value("\${kbap.batch.content.chunk-size:10}") chunkSize: Int,
    ): FoodContentJob = FoodContentJob(foodContentBatchService, chunkSize)

    @Bean
    @ConditionalOnProperty(prefix = "kbap.batch.content.runner", name = ["enabled"], havingValue = "true")
    fun foodContentJobRunner(job: FoodContentJob): ApplicationRunner = ApplicationRunner { job.run() }
}
