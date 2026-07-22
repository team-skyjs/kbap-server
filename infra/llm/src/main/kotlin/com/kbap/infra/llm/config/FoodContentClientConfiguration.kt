package com.kbap.infra.llm.config

import com.kbap.core.food.FoodAvoidanceAssessmentClient
import com.kbap.core.food.FoodDescriptionClient
import com.kbap.core.food.FoodImageGenerationClient
import com.kbap.core.food.FoodNameTranslationClient
import com.kbap.core.storage.StorageObjectStore
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.food.OpenAiFoodImageGenerationClient
import com.kbap.infra.llm.food.SpringAiFoodAvoidanceAssessmentClient
import com.kbap.infra.llm.food.SpringAiFoodDescriptionClient
import com.kbap.infra.llm.food.SpringAiFoodNameTranslationClient
import org.springframework.ai.image.ImageModel
import org.springframework.ai.openai.OpenAiImageModel
import org.springframework.ai.openai.OpenAiImageOptions
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
    fun foodAvoidanceAssessmentClient(fanoutClient: LlmFanoutClient): FoodAvoidanceAssessmentClient =
        SpringAiFoodAvoidanceAssessmentClient(fanoutClient)

    // 이미지 생성은 텍스트 3작업 실운영 검증 후 개방 — 주석 해제 시 그대로 동작한다.
    // @Bean
    // @ConditionalOnProperty(prefix = "kbap.llm.image", name = ["enabled"], havingValue = "true")
    // fun foodImageGenerationClient(
    //     properties: LlmModelProperties,
    //     storageObjectStore: StorageObjectStore,
    // ): FoodImageGenerationClient =
    //     OpenAiFoodImageGenerationClient(imageModel(properties.image), storageObjectStore)
    //
    // private fun imageModel(props: LlmModelProperties.ImageProps): ImageModel {
    //     val builder = OpenAiImageOptions.builder()
    //     builder.responseFormat("b64_json")
    //     props.apiKey?.let { builder.apiKey(it) }
    //     props.baseUrl?.let { builder.baseUrl(it) }
    //     props.model?.let { builder.model(it) }
    //     props.size?.let { builder.size(it) }
    //     return OpenAiImageModel.builder().options(builder.build()).build()
    // }
}
