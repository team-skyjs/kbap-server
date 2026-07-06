package com.meogo.infra.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("meogo.llm")
data class LlmModelProperties(
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
        val pricing: PricingProps = PricingProps(),
    )

    data class PricingProps(
        val inputUsdPerMillionTokens: Double = 0.0,
        val outputUsdPerMillionTokens: Double = 0.0,
    )
}
