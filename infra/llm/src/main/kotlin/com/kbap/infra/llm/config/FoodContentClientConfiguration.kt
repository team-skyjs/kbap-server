package com.kbap.infra.llm.config

import com.kbap.core.food.FoodAvoidanceAssessmentClient
import com.kbap.core.food.FoodDescriptionClient
import com.kbap.core.food.FoodImageBatchClient
import com.kbap.core.food.FoodNameTranslationClient
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.food.OpenAiFoodImageBatchClient
import com.kbap.infra.llm.food.SpringAiFoodAvoidanceAssessmentClient
import com.kbap.infra.llm.food.SpringAiFoodDescriptionClient
import com.kbap.infra.llm.food.SpringAiFoodNameTranslationClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FoodContentClientConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.openai", name = ["enabled"], havingValue = "true")
    fun foodNameTranslationClient(
        @Qualifier("openAiModelCaller") caller: LlmModelCaller,
    ): FoodNameTranslationClient = SpringAiFoodNameTranslationClient(caller)

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.openai", name = ["enabled"], havingValue = "true")
    fun foodDescriptionClient(
        @Qualifier("openAiModelCaller") caller: LlmModelCaller,
    ): FoodDescriptionClient = SpringAiFoodDescriptionClient(caller)

    @Bean
    fun foodAvoidanceAssessmentClient(
        fanoutClient: LlmFanoutClient,
        @Value("\${kbap.llm.avoidance.min-agreement:2}") minAgreement: Int,
    ): FoodAvoidanceAssessmentClient = SpringAiFoodAvoidanceAssessmentClient(fanoutClient, minAgreement)

    @Bean
    fun foodImageBatchClient(properties: LlmModelProperties): FoodImageBatchClient =
        OpenAiFoodImageBatchClient(properties.image)
}
