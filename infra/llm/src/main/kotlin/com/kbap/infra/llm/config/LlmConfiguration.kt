package com.kbap.infra.llm.config

import com.google.genai.Client
import com.kbap.domain.scan.ScannedNameInterpreter
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.menu.ScannedNameParser
import com.kbap.infra.llm.menu.UpstageScannedNameInterpreter
import com.kbap.infra.llm.model.LlmModelId
import com.kbap.infra.llm.model.LlmPricing
import com.kbap.infra.llm.provider.SpringAiModelCaller
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
    @ConditionalOnProperty(prefix = "kbap.llm.upstage", name = ["enabled"], havingValue = "true")
    fun scannedNameInterpreter(properties: LlmModelProperties): ScannedNameInterpreter =
        UpstageScannedNameInterpreter(upstageModelCaller(properties), ScannedNameParser())

    @Bean
    fun llmFanoutExecutor(): Executor = Executors.newVirtualThreadPerTaskExecutor()

    @Bean
    fun llmFanoutClient(
        callers: List<LlmModelCaller>,
        executor: Executor,
        properties: LlmModelProperties,
    ): LlmFanoutClient = LlmFanoutClient(callers, executor, properties.callTimeout)

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
