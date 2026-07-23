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

    // 이미지 배치 seam(KB-226) — 빈 조립은 무조건, API 키 검증은 첫 호출 시점(관리자 제출·회수는 키 없이는 실패).
    @Bean
    fun foodImageBatchClient(properties: LlmModelProperties): FoodImageBatchClient =
        OpenAiFoodImageBatchClient(properties.image)
}
