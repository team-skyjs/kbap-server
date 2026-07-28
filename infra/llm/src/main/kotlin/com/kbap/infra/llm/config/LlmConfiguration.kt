package com.kbap.infra.llm.config

import com.google.genai.Client
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.menu.MenuBoardResultParser
import com.kbap.infra.llm.menu.OpenAiMenuBoardVisionExtractor
import com.kbap.infra.llm.model.LlmModelId
import com.kbap.infra.llm.model.LlmPricing
import com.kbap.infra.llm.provider.SpringAiModelCaller
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Configuration
@EnableConfigurationProperties(LlmModelProperties::class)
class LlmConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.openai", name = ["enabled"], havingValue = "true")
    fun openAiModelCaller(properties: LlmModelProperties): LlmModelCaller =
        SpringAiModelCaller(
            LlmModelId.OPENAI,
            openAiChatModel(
                LlmModelId.OPENAI,
                properties.openai,
                resolveOpenAiBaseUrl(properties.openai.baseUrl),
                properties.callTimeout,
            ),
            pricingOf(properties.openai, properties.usdToKrw),
        )

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.openai", name = ["enabled"], havingValue = "true")
    fun avoidanceOpenAiModelCaller(properties: LlmModelProperties): LlmModelCaller {
        val props = avoidanceOpenAiProps(properties.openai, properties.avoidance)
        return SpringAiModelCaller(
            LlmModelId.OPENAI,
            openAiChatModel(
                LlmModelId.OPENAI,
                props,
                resolveOpenAiBaseUrl(props.baseUrl),
                properties.callTimeout,
            ),
            pricingOf(props, properties.usdToKrw),
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.upstage", name = ["enabled"], havingValue = "true")
    fun upstageModelCaller(properties: LlmModelProperties): LlmModelCaller =
        SpringAiModelCaller(
            LlmModelId.UPSTAGE,
            openAiChatModel(
                LlmModelId.UPSTAGE,
                properties.upstage,
                properties.upstage.baseUrl ?: DEFAULT_UPSTAGE_BASE_URL,
                properties.callTimeout,
            ),
            pricingOf(properties.upstage, properties.usdToKrw),
        )

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.gemini", name = ["enabled"], havingValue = "true")
    fun geminiModelCaller(properties: LlmModelProperties): LlmModelCaller =
        SpringAiModelCaller(
            LlmModelId.GEMINI,
            geminiChatModel(properties.gemini),
            pricingOf(properties.gemini, properties.usdToKrw),
        )

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
    fun llmFanoutExecutor(): Executor = Executors.newVirtualThreadPerTaskExecutor()

    // fanout 은 기피성분 조사 전용 — 공용 openAiModelCaller(번역·설명용) 대신 avoidance 오버라이드 caller 를 태운다.
    @Bean
    fun llmFanoutClient(
        @Qualifier("avoidanceOpenAiModelCaller") avoidanceOpenAiCaller: LlmModelCaller?,
        @Qualifier("upstageModelCaller") upstageCaller: LlmModelCaller?,
        @Qualifier("geminiModelCaller") geminiCaller: LlmModelCaller?,
        // @EnableScheduling 의 taskScheduler 도 Executor 라 타입 주입이 모호하다 — 이름으로 고정(KB-226).
        @Qualifier("llmFanoutExecutor") executor: Executor,
        properties: LlmModelProperties,
    ): LlmFanoutClient =
        LlmFanoutClient(listOfNotNull(avoidanceOpenAiCaller, upstageCaller, geminiCaller), executor, properties.callTimeout)

    private fun openAiChatModel(
        modelId: LlmModelId,
        props: LlmModelProperties.ModelProps,
        baseUrl: String,
        callTimeout: Duration,
    ): ChatModel =
        OpenAiChatModel.builder()
            .options(openAiChatOptions(modelId, props, baseUrl, callTimeout))
            .build()

    private fun pricingOf(props: LlmModelProperties.ModelProps, usdToKrw: Double): LlmPricing =
        LlmPricing(
            inputUsdPerMillionTokens = props.pricing.inputUsdPerMillionTokens,
            outputUsdPerMillionTokens = props.pricing.outputUsdPerMillionTokens,
            usdToKrw = usdToKrw,
        )

    private fun geminiChatModel(props: LlmModelProperties.ModelProps): ChatModel {
        val client = Client.builder()
            .apiKey(props.apiKey)
            .build()
        return GoogleGenAiChatModel.builder()
            .genAiClient(client)
            .options(geminiChatOptions(props))
            .build()
    }

    companion object {
        internal const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_UPSTAGE_BASE_URL = "https://api.upstage.ai/v1"

        internal fun openAiChatOptions(
            modelId: LlmModelId,
            props: LlmModelProperties.ModelProps,
            baseUrl: String,
            callTimeout: Duration,
        ): OpenAiChatOptions {
            val builder = OpenAiChatOptions.builder()
            builder.apiKey(requireOpenAiApiKey(modelId, props.apiKey))
            builder.baseUrl(baseUrl)
            builder.timeout(callTimeout)
            props.maxRetries?.let { builder.maxRetries(it) }
            props.model?.let { builder.model(it) }
            props.maxOutputTokens?.let {
                if (modelId == LlmModelId.OPENAI) builder.maxCompletionTokens(it) else builder.maxTokens(it)
            }
            props.temperature?.let { builder.temperature(it) }
            if (modelId == LlmModelId.OPENAI) {
                props.reasoningEffort?.let { builder.reasoningEffort(it) }
            }
            return builder.build()
        }

        internal fun geminiChatOptions(props: LlmModelProperties.ModelProps): GoogleGenAiChatOptions {
            val builder = GoogleGenAiChatOptions.builder()
            props.model?.let { builder.model(it) }
            props.maxOutputTokens?.let { builder.maxOutputTokens(it) }
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

        internal fun requireOpenAiApiKey(modelId: LlmModelId, configured: String?): String {
            if (configured.isNullOrBlank()) {
                val property = "kbap.llm.${modelId.name.lowercase()}.api-key"
                throw IllegalStateException(
                    "LLM 모델 $modelId 의 api-key 가 비어 있습니다. $property 를 설정하세요" +
                        "(배포 환경변수로 주입 — 환경변수 폴백은 허용하지 않습니다).",
                )
            }
            return configured
        }
    }
}
