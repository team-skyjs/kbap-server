package com.meogo.infra.llm.config

import com.google.genai.Client
import com.meogo.infra.llm.client.LlmFanoutClient
import com.meogo.infra.llm.client.LlmModelCaller
import com.meogo.infra.llm.model.LlmModelId
import com.meogo.infra.llm.provider.SpringAiModelCaller
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Configuration
@EnableConfigurationProperties(LlmModelProperties::class)
class LlmConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "meogo.llm.openai", name = ["enabled"], havingValue = "true")
    fun openAiModelCaller(properties: LlmModelProperties): LlmModelCaller =
        SpringAiModelCaller(
            LlmModelId.OPENAI,
            openAiChatModel(LlmModelId.OPENAI, properties.openai, resolveOpenAiBaseUrl(properties.openai.baseUrl)),
        )

    @Bean
    @ConditionalOnProperty(prefix = "meogo.llm.upstage", name = ["enabled"], havingValue = "true")
    fun upstageModelCaller(properties: LlmModelProperties): LlmModelCaller =
        SpringAiModelCaller(
            LlmModelId.UPSTAGE,
            openAiChatModel(LlmModelId.UPSTAGE, properties.upstage, properties.upstage.baseUrl ?: DEFAULT_UPSTAGE_BASE_URL),
        )

    @Bean
    @ConditionalOnProperty(prefix = "meogo.llm.gemini", name = ["enabled"], havingValue = "true")
    fun geminiModelCaller(properties: LlmModelProperties): LlmModelCaller =
        SpringAiModelCaller(LlmModelId.GEMINI, geminiChatModel(properties.gemini))

    @Bean
    fun llmFanoutExecutor(): Executor = Executors.newVirtualThreadPerTaskExecutor()

    @Bean
    fun llmFanoutClient(callers: List<LlmModelCaller>, executor: Executor): LlmFanoutClient =
        LlmFanoutClient(callers, executor)

    private fun openAiChatModel(modelId: LlmModelId, props: LlmModelProperties.ModelProps, baseUrl: String): ChatModel {
        val optionsBuilder = OpenAiChatOptions.builder()
        optionsBuilder.apiKey(requireOpenAiApiKey(modelId, props.apiKey))
        optionsBuilder.baseUrl(baseUrl)
        props.model?.let { optionsBuilder.model(it) }
        return OpenAiChatModel.builder()
            .options(optionsBuilder.build())
            .build()
    }

    private fun geminiChatModel(props: LlmModelProperties.ModelProps): ChatModel {
        val client = Client.builder()
            .apiKey(props.apiKey)
            .build()
        val optionsBuilder = GoogleGenAiChatOptions.builder()
        props.model?.let { optionsBuilder.model(it) }
        return GoogleGenAiChatModel.builder()
            .genAiClient(client)
            .options(optionsBuilder.build())
            .build()
    }

    companion object {
        internal const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_UPSTAGE_BASE_URL = "https://api.upstage.ai/v1"

        internal fun resolveOpenAiBaseUrl(configured: String?): String = configured ?: DEFAULT_OPENAI_BASE_URL

        internal fun requireOpenAiApiKey(modelId: LlmModelId, configured: String?): String {
            if (configured.isNullOrBlank()) {
                throw IllegalStateException("LLM 모델 $modelId 의 api-key 가 설정되지 않았습니다 (환경변수 폴백 미허용)")
            }
            return configured
        }
    }
}
