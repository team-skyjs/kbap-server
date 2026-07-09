package com.meogo.infra.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("meogo.llm")
data class LlmModelProperties(
    val callTimeout: Duration = Duration.ofSeconds(30),
    val usdToKrw: Double = 1500.0,
    val openai: ModelProps = ModelProps(),
    val upstage: ModelProps = ModelProps(),
    val gemini: ModelProps = ModelProps(),
) {
    data class ModelProps(
        val enabled: Boolean = false,
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val maxOutputTokens: Int? = null,
        val reasoningEffort: String? = null,
        val temperature: Double? = null,
        val pricing: PricingProps = PricingProps(),
    )

    data class PricingProps(
        val inputUsdPerMillionTokens: Double = 0.0,
        val outputUsdPerMillionTokens: Double = 0.0,
    )
}
