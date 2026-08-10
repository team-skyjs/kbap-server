package com.kbap.infra.llm.config

import com.kbap.common.port.llm.FoodAvoidanceAssessmentClient
import com.kbap.common.port.llm.FoodDescriptionClient
import com.kbap.common.port.llm.FoodImageBatchClient
import com.kbap.common.port.llm.FoodNameTranslationClient
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.food.OpenAiFoodImageBatchClient
import com.kbap.infra.llm.food.SpringAiFoodAvoidanceAssessmentClient
import com.kbap.infra.llm.food.SpringAiFoodDescriptionClient
import com.kbap.infra.llm.food.SpringAiFoodNameTranslationClient
import org.springframework.beans.factory.annotation.Qualifier
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
    @ConditionalOnProperty(prefix = "kbap.llm.openai", name = ["enabled"], havingValue = "true")
    fun foodAvoidanceAssessmentClient(
        @Qualifier("avoidanceOpenAiModelCaller") caller: LlmModelCaller,
    ): FoodAvoidanceAssessmentClient = SpringAiFoodAvoidanceAssessmentClient(caller)

    @Bean
    fun foodImageBatchClient(properties: LlmModelProperties): FoodImageBatchClient =
        OpenAiFoodImageBatchClient(properties.image)
}
