package com.kbap.common.infra.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("kbap.llm")
data class LlmModelProperties(
    val usdToKrw: Double = 1500.0,
    val vision: VisionProps = VisionProps(),
    val image: ImageProps = ImageProps(),
    val embedding: EmbeddingProps = EmbeddingProps(),
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

    data class VisionProps(
        val enabled: Boolean = false,
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val imageBaseUrl: String = "",
        val temperature: Double? = null,
        val maxRetries: Int = 0,
        val timeout: Duration = Duration.ofSeconds(60),
        val retryBudget: Duration = Duration.ofSeconds(10),
        val pricing: PricingProps = PricingProps(
            inputUsdPerMillionTokens = 0.2,
            outputUsdPerMillionTokens = 1.2,
        ),
    )

    data class PricingProps(
        val inputUsdPerMillionTokens: Double = 0.0,
        val outputUsdPerMillionTokens: Double = 0.0,
    )
}
