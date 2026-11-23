package com.kbap.common.infra.llm.config

import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.llm.FoodImageBatchClient
import com.kbap.common.port.llm.FoodImageGenerator
import com.kbap.common.port.llm.IngredientDecider
import com.kbap.common.port.llm.MenuNameEstimator
import com.kbap.common.infra.llm.menu.OpenAiMenuNameEstimator
import com.kbap.common.port.llm.ProcessedFoodTextTranslator
import com.kbap.common.port.llm.ReviewTextGenerator
import com.kbap.common.port.llm.TextEmbeddingClient
import com.kbap.common.port.llm.TextTranslator
import com.kbap.common.infra.llm.embedding.OpenAiTextEmbeddingClient
import com.kbap.common.infra.llm.food.OpenAiFoodImageBatchClient
import com.kbap.common.infra.llm.food.OpenAiFoodImageGenerator
import com.kbap.common.infra.llm.ingredient.OpenAiIngredientDecider
import com.kbap.common.infra.llm.menu.MenuBoardResultParser
import com.kbap.common.infra.llm.menu.OpenAiMenuBoardVisionClient
import com.kbap.common.infra.llm.review.OpenAiReviewTextGenerator
import com.kbap.common.infra.llm.translation.OpenAiTextTranslator
import com.kbap.common.infra.llm.translation.ProcessedFoodNamePrompt
import com.kbap.common.infra.llm.model.LlmPricing
import com.kbap.common.infra.llm.claude.ClaudeChat
import com.kbap.common.infra.llm.ingredient.ClaudeIngredientDecider
import com.kbap.common.infra.llm.menu.ClaudeMenuBoardVisionClient
import com.kbap.common.infra.llm.menu.ClaudeMenuNameEstimator
import com.kbap.common.infra.llm.menu.MenuBoardVisionClient
import com.kbap.common.infra.llm.review.ClaudeReviewTextGenerator
import com.kbap.common.infra.llm.translation.ClaudeTextTranslator
import com.anthropic.client.okhttp.AnthropicOkHttpClient
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
    fun menuBoardVisionClient(
        properties: LlmModelProperties,
        eventPublisher: ApplicationEventPublisher,
    ): MenuBoardVisionClient {
        val props = properties.vision
        if (!props.usesOpenAi()) {
            return ClaudeMenuBoardVisionClient(
                chat = claudeChat(props, properties.usdToKrw, eventPublisher),
                parser = MenuBoardResultParser(),
                imageBaseUrl = props.imageBaseUrl,
                retryBudget = props.retryBudget,
            )
        }
        val openAi = props.forOpenAi()
        return OpenAiMenuBoardVisionClient(
            chatModel = openAiChatModel(openAi),
            parser = MenuBoardResultParser(),
            imageBaseUrl = openAi.imageBaseUrl,
            pricing = pricingOf(openAi, properties.usdToKrw),
            configuredModelName = openAi.model.orEmpty(),
            eventPublisher = eventPublisher,
            retryBudget = openAi.retryBudget,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.review", name = ["enabled"], havingValue = "true")
    fun reviewTextGenerator(
        properties: LlmModelProperties,
        eventPublisher: ApplicationEventPublisher,
    ): ReviewTextGenerator {
        val props = properties.review
        if (!props.usesOpenAi()) return ClaudeReviewTextGenerator(claudeChat(props, properties.usdToKrw, eventPublisher))
        val openAi = props.forOpenAi()
        return OpenAiReviewTextGenerator(
            chatModel = openAiChatModel(openAi),
            pricing = pricingOf(openAi, properties.usdToKrw),
            configuredModelName = openAi.model.orEmpty(),
            eventPublisher = eventPublisher,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.translation", name = ["enabled"], havingValue = "true")
    fun textTranslator(
        properties: LlmModelProperties,
        eventPublisher: ApplicationEventPublisher,
    ): TextTranslator {
        val props = properties.translation
        if (!props.usesOpenAi()) return ClaudeTextTranslator(claudeChat(props, properties.usdToKrw, eventPublisher))
        val openAi = props.forOpenAi()
        return OpenAiTextTranslator(
            chatModel = openAiChatModel(openAi),
            pricing = pricingOf(openAi, properties.usdToKrw),
            configuredModelName = openAi.model.orEmpty(),
            eventPublisher = eventPublisher,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.processed-food-translation", name = ["enabled"], havingValue = "true")
    fun processedFoodTextTranslator(
        properties: LlmModelProperties,
        eventPublisher: ApplicationEventPublisher,
    ): ProcessedFoodTextTranslator {
        val props = properties.processedFoodTranslation
        val chat = if (props.usesOpenAi()) null else claudeChat(props, properties.usdToKrw, eventPublisher)
        val openAi = props.forOpenAi()
        val chatModel = if (chat == null) openAiChatModel(openAi) else null
        fun translator(format: OpenAiTextTranslator.Format): TextTranslator =
            chat?.let { ClaudeTextTranslator(it, format) }
                ?: OpenAiTextTranslator(chatModel = chatModel!!, pricing = pricingOf(openAi, properties.usdToKrw), configuredModelName = openAi.model.orEmpty(), eventPublisher = eventPublisher, format = format)
        val labelTranslator = translator(OpenAiTextTranslator.Format.PROCESSED_FOOD)
        val nameTranslator = translator(OpenAiTextTranslator.Format.PROCESSED_FOOD_NAME)
        return object : ProcessedFoodTextTranslator {
            override fun translate(text: String, target: LanguageCode) = labelTranslator.translate(text, target)

            override fun translateName(name: String, target: LanguageCode) = nameTranslator.translate(name, target)

            override fun isAcceptableName(translated: String, source: String) = runCatching { ProcessedFoodNamePrompt.parse(translated, source) }.isSuccess

            override fun officialLatinOf(name: String) = ProcessedFoodNamePrompt.officialLatinOf(name)
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.ingredient-decision", name = ["enabled"], havingValue = "true")
    fun ingredientDecider(
        properties: LlmModelProperties,
        eventPublisher: ApplicationEventPublisher,
    ): IngredientDecider {
        val props = properties.ingredientDecision
        if (!props.usesOpenAi()) return ClaudeIngredientDecider(claudeChat(props, properties.usdToKrw, eventPublisher))
        val openAi = props.forOpenAi()
        return OpenAiIngredientDecider(
            chatModel = openAiChatModel(openAi),
            pricing = pricingOf(openAi, properties.usdToKrw),
            configuredModelName = openAi.model.orEmpty(),
            eventPublisher = eventPublisher,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "kbap.llm.menu-estimate", name = ["enabled"], havingValue = "true")
    fun menuNameEstimator(
        properties: LlmModelProperties,
        eventPublisher: ApplicationEventPublisher,
    ): MenuNameEstimator {
        val props = properties.menuEstimate
        if (!props.usesOpenAi()) return ClaudeMenuNameEstimator(claudeChat(props, properties.usdToKrw, eventPublisher))
        val openAi = props.forOpenAi()
        return OpenAiMenuNameEstimator(
            chatModel = openAiChatModel(openAi),
            pricing = pricingOf(openAi, properties.usdToKrw),
            configuredModelName = openAi.model.orEmpty(),
            eventPublisher = eventPublisher,
        )
    }

    @Bean
    fun foodImageBatchClient(properties: LlmModelProperties): FoodImageBatchClient =
        OpenAiFoodImageBatchClient(properties.image)

    @Bean
    fun foodImageGenerator(properties: LlmModelProperties): FoodImageGenerator =
        OpenAiFoodImageGenerator(properties.image)

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
            builder.maxRetries(props.maxRetries)
            props.model?.let { builder.model(it) }
            props.temperature?.let { builder.temperature(it) }
            props.reasoningEffort?.let { builder.reasoningEffort(it) }
            return builder.build()
        }

        internal fun resolveOpenAiBaseUrl(configured: String?): String = configured ?: DEFAULT_OPENAI_BASE_URL

        internal fun LlmModelProperties.VisionProps.usesOpenAi(): Boolean =
            when (provider.trim().lowercase()) {
                LlmModelProperties.PROVIDER_ANTHROPIC -> false
                LlmModelProperties.PROVIDER_OPENAI -> true
                else -> throw IllegalArgumentException("kbap.llm.*.provider 는 anthropic 또는 openai 여야 합니다(현재: $provider)")
            }

        // openai 블록이 있으면 그 값으로 덮어 기존 OpenAI 조립에 넘긴다 — 없으면 상위 값을 OpenAI 설정으로 본다.
        internal fun LlmModelProperties.VisionProps.forOpenAi(): LlmModelProperties.VisionProps =
            openai?.let {
                copy(
                    apiKey = it.apiKey,
                    baseUrl = it.baseUrl,
                    model = it.model,
                    temperature = it.temperature,
                    reasoningEffort = it.reasoningEffort,
                    pricing = it.pricing ?: pricing,
                )
            } ?: this

        private fun openAiChatModel(props: LlmModelProperties.VisionProps): OpenAiChatModel =
            OpenAiChatModel.builder()
                .options(visionChatOptions(props, resolveOpenAiBaseUrl(props.baseUrl), props.timeout))
                .httpClientBuilderCustomizer { it.timeout(props.timeout) }
                .build()

        private fun pricingOf(props: LlmModelProperties.VisionProps, usdToKrw: Double) =
            LlmPricing(
                inputUsdPerMillionTokens = props.pricing.inputUsdPerMillionTokens,
                outputUsdPerMillionTokens = props.pricing.outputUsdPerMillionTokens,
                usdToKrw = usdToKrw,
            )

        // 호출 전체 시간은 timeout 이 묶는다(SDK request timeout). max-retries 는 배포 설정상 0 — 재시도가 제한 시간을 여러 번 쓰지 않는다.
        internal fun claudeChat(
            props: LlmModelProperties.VisionProps,
            usdToKrw: Double,
            eventPublisher: ApplicationEventPublisher,
        ): ClaudeChat {
            require(!props.apiKey.isNullOrBlank()) { "kbap.llm.*.api-key(ANTHROPIC_API_KEY) 가 비어 있습니다(배포 환경변수로 주입)." }
            require(!props.model.isNullOrBlank()) { "kbap.llm.*.model 이 비어 있습니다." }
            val client = AnthropicOkHttpClient.builder()
                .apiKey(props.apiKey)
                .apply { props.baseUrl?.let { baseUrl(it) } }
                .timeout(props.timeout)
                .maxRetries(props.maxRetries)
                .build()
            return ClaudeChat(client, props.model, props.reasoningEffort, pricingOf(props, usdToKrw), eventPublisher)
        }
    }
}
