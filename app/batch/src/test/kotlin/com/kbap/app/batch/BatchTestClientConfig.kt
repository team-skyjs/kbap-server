package com.kbap.app.batch

import com.kbap.core.food.FoodAvoidanceAssessmentClient
import com.kbap.core.food.FoodAvoidanceAssessmentResult
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration
class BatchTestClientConfig {
    @Bean
    fun avoidanceClient(): FoodAvoidanceAssessmentClient =
        FoodAvoidanceAssessmentClient { _, _ -> FoodAvoidanceAssessmentResult(emptyList(), 0) }
}
