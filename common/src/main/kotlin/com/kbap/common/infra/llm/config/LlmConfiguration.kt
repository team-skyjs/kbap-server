package com.kbap.common.infra.llm.config

import com.kbap.common.port.llm.FoodImageBatchClient
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.TextEmbeddingClient
import com.kbap.common.infra.llm.embedding.OpenAiTextEmbeddingClient
import com.kbap.common.infra.llm.food.OpenAiFoodImageBatchClient
import com.kbap.common.infra.llm.menu.MenuBoardResultParser
import com.kbap.common.infra.llm.menu.OpenAiMenuBoardVisionExtractor
import com.kbap.common.infra.llm.model.LlmPricing
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@EnableConfigurationProperties(LlmModelProperties::class)
class LlmConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.vision", name = ["enabled"], havingValue = "true")
    fun menuBoardVisionExtractor(
        properties: LlmModelProperties,
        eventPublisher: ApplicationEventPublisher,
    ): MenuBoardVisionExtractor {
        val props = properties.vision
        val chatModel = OpenAiChatModel.builder()
            .options(visionChatOptions(props, resolveOpenAiBaseUrl(props.baseUrl), props.timeout))
            .httpClientBuilderCustomizer { it.timeout(props.timeout) }
            .build()
        val pricing = LlmPricing(
            inputUsdPerMillionTokens = props.pricing.inputUsdPerMillionTokens,
            outputUsdPerMillionTokens = props.pricing.outputUsdPerMillionTokens,
            usdToKrw = properties.usdToKrw,
        )
        return OpenAiMenuBoardVisionExtractor(
            chatModel = chatModel,
            parser = MenuBoardResultParser(),
            imageBaseUrl = props.imageBaseUrl,
            pricing = pricing,
            configuredModelName = props.model.orEmpty(),
            eventPublisher = eventPublisher,
        )
    }

    @Bean
    fun foodImageBatchClient(properties: LlmModelProperties): FoodImageBatchClient =
        OpenAiFoodImageBatchClient(properties.image)

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.embedding", name = ["enabled"], havingValue = "true")
    fun textEmbeddingClient(properties: LlmModelProperties): TextEmbeddingClient =
        OpenAiTextEmbeddingClient(properties.embedding)

    companion object {
        internal const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"

        internal fun visionChatOptions(
            props: LlmModelProperties.VisionProps,
            baseUrl: String,
            callTimeout: Duration,
        ): OpenAiChatOptions {
            val apiKey = props.apiKey
            require(!apiKey.isNullOrBlank()) {
                "kbap.llm.vision.api-key 가 비어 있습니다(배포 환경변수로 주입)."
            }
            val builder = OpenAiChatOptions.builder()
            builder.apiKey(apiKey)
            builder.baseUrl(baseUrl)
            builder.timeout(callTimeout)
            props.maxRetries?.let { builder.maxRetries(it) }
            props.model?.let { builder.model(it) }
            props.temperature?.let { builder.temperature(it) }
            return builder.build()
        }

        internal fun resolveOpenAiBaseUrl(configured: String?): String = configured ?: DEFAULT_OPENAI_BASE_URL
    }
}
