package com.kbap.common.infra.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("kbap.llm")
data class LlmModelProperties(
    val usdToKrw: Double = 1500.0,
    val vision: VisionProps = VisionProps(),
    val image: ImageProps = ImageProps(),
    val embedding: EmbeddingProps = EmbeddingProps(),
    val review: VisionProps = VisionProps(),
    val translation: VisionProps = VisionProps(),
    val processedFoodTranslation: VisionProps = VisionProps(),
    val ingredientDecision: VisionProps = VisionProps(),
    val menuEstimate: VisionProps = VisionProps(),
) {
    data class EmbeddingProps(
        val enabled: Boolean = false,
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val model: String = "text-embedding-3-small",
        val dimension: Int = 256,
        val timeout: Duration = Duration.ofSeconds(30),
    )

    data class ImageProps(
        val enabled: Boolean = false,
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val size: String? = null,
        val quality: String? = null,
        val outputFormat: String? = null,
        val outputCompression: Int? = null,
    )

    // 텍스트·비전 caller 공통. provider=anthropic(기본)이면 아래 값이 Claude 설정이고, provider=openai 면 openai 블록(없으면 이 값 그대로)으로
    // 기존 OpenAI 조립을 쓴다 — 배포 없이 환경변수 전환만으로 되돌리기 위한 스위치. reasoning-effort 는 Claude 에서 output_config.effort 로 간다.
    data class VisionProps(
        val enabled: Boolean = false,
        val provider: String = PROVIDER_ANTHROPIC,
        val openai: OpenAiProps? = null,
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val imageBaseUrl: String = "",
        val temperature: Double? = null,
        val reasoningEffort: String? = null,
        val maxRetries: Int = 0,
        val timeout: Duration = Duration.ofSeconds(60),
        val retryBudget: Duration = Duration.ofSeconds(10),
        val pricing: PricingProps = PricingProps(
            inputUsdPerMillionTokens = 0.2,
            outputUsdPerMillionTokens = 1.2,
        ),
    )

    data class OpenAiProps(
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val temperature: Double? = null,
        val reasoningEffort: String? = null,
        val pricing: PricingProps? = null,
    )

    data class PricingProps(
        val inputUsdPerMillionTokens: Double = 0.0,
        val outputUsdPerMillionTokens: Double = 0.0,
    )

    companion object {
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENAI = "openai"
    }
}
