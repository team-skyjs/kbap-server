package com.kbap.infra.llm.config

import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.TextEmbeddingClient
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.embedding.SpringAiTextEmbeddingClient
import com.kbap.infra.llm.menu.MenuBoardResultParser
import com.kbap.infra.llm.menu.OpenAiMenuBoardVisionExtractor
import com.kbap.infra.llm.model.LlmPricing
import com.kbap.infra.llm.provider.SpringAiModelCaller
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat
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
    @ConditionalOnProperty(prefix = "kbap.llm.openai", name = ["enabled"], havingValue = "true")
    fun openAiModelCaller(properties: LlmModelProperties): LlmModelCaller =
        SpringAiModelCaller(
            properties.openai.model.orEmpty(),
            openAiChatModel(
                properties.openai,
                resolveOpenAiBaseUrl(properties.openai.baseUrl),
                properties.callTimeout,
                OPENAI_API_KEY_PROPERTY,
            ),
            pricingOf(properties.openai, properties.usdToKrw),
        )

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.openai", name = ["enabled"], havingValue = "true")
    fun avoidanceOpenAiModelCaller(properties: LlmModelProperties): LlmModelCaller {
        val props = avoidanceOpenAiProps(properties.openai, properties.avoidance)
        return SpringAiModelCaller(
            props.model.orEmpty(),
            openAiChatModel(
                props,
                resolveOpenAiBaseUrl(props.baseUrl),
                properties.callTimeout,
                OPENAI_API_KEY_PROPERTY,
            ),
            pricingOf(props, properties.usdToKrw),
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.vision", name = ["enabled"], havingValue = "true")
    fun menuBoardVisionExtractor(
        properties: LlmModelProperties,
        eventPublisher: ApplicationEventPublisher,
    ): MenuBoardVisionExtractor {
        val props = properties.vision
        val chatModel = OpenAiChatModel.builder()
            .options(visionChatOptions(props, resolveOpenAiBaseUrl(props.baseUrl), props.timeout))
            // OpenAiChatOptions.timeout 은 spring-ai-openai 2.0 에서 소비되지 않는다(죽은 필드) —
            // 실제 okhttp 타임아웃은 http client 빌더로만 설정된다. vision 은 사진 해석이라 기본값(짧음)으로는 초과한다.
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
    @ConditionalOnProperty(prefix = "kbap.llm.embedding", name = ["enabled"], havingValue = "true")
    fun textEmbeddingClient(properties: LlmModelProperties): TextEmbeddingClient {
        val props = properties.embedding
        val api = TitanEmbeddingBedrockApi(props.model, props.region, props.timeout)
        return SpringAiTextEmbeddingClient(
            BedrockTitanEmbeddingModel(api, ObservationRegistry.NOOP),
            props.dimension,
        )
    }

    private fun openAiChatModel(
        props: LlmModelProperties.ModelProps,
        baseUrl: String,
        callTimeout: Duration,
        apiKeyProperty: String,
    ): ChatModel =
        OpenAiChatModel.builder()
            .options(openAiChatOptions(props, baseUrl, callTimeout, apiKeyProperty))
            .build()

    private fun pricingOf(props: LlmModelProperties.ModelProps, usdToKrw: Double): LlmPricing =
        LlmPricing(
            inputUsdPerMillionTokens = props.pricing.inputUsdPerMillionTokens,
            outputUsdPerMillionTokens = props.pricing.outputUsdPerMillionTokens,
            usdToKrw = usdToKrw,
        )

    companion object {
        internal const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        internal const val OPENAI_API_KEY_PROPERTY = "kbap.llm.openai.api-key"

        internal fun openAiChatOptions(
            props: LlmModelProperties.ModelProps,
            baseUrl: String,
            callTimeout: Duration,
            apiKeyProperty: String = OPENAI_API_KEY_PROPERTY,
        ): OpenAiChatOptions {
            val builder = OpenAiChatOptions.builder()
            builder.apiKey(requireOpenAiApiKey(apiKeyProperty, props.apiKey))
            builder.baseUrl(baseUrl)
            builder.timeout(callTimeout)
            props.maxRetries?.let { builder.maxRetries(it) }
            props.model?.let { builder.model(it) }
            // 추론 모델은 max_tokens 를 받지 않는다 — 전 모델이 OpenAI 추론 계열이므로 항상 maxCompletionTokens.
            props.maxOutputTokens?.let { builder.maxCompletionTokens(it) }
            props.temperature?.let { builder.temperature(it) }
            props.reasoningEffort?.let { builder.reasoningEffort(it) }
            return builder.build()
        }

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
            builder.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
            return builder.build()
        }

        internal fun avoidanceOpenAiProps(
            openai: LlmModelProperties.ModelProps,
            avoidance: LlmModelProperties.AvoidanceProps,
        ): LlmModelProperties.ModelProps =
            openai.copy(
                model = avoidance.model ?: openai.model,
                maxOutputTokens = avoidance.maxOutputTokens ?: openai.maxOutputTokens,
                reasoningEffort = avoidance.reasoningEffort ?: openai.reasoningEffort,
                pricing = avoidance.pricing ?: openai.pricing,
            )

        internal fun resolveOpenAiBaseUrl(configured: String?): String = configured ?: DEFAULT_OPENAI_BASE_URL

        internal fun requireOpenAiApiKey(property: String, configured: String?): String {
            if (configured.isNullOrBlank()) {
                throw IllegalStateException(
                    "LLM api-key 가 비어 있습니다. $property 를 설정하세요" +
                        "(배포 환경변수로 주입 — 환경변수 폴백은 허용하지 않습니다).",
                )
            }
            return configured
        }
    }
}
