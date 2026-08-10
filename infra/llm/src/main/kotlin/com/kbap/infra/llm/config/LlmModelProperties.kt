package com.kbap.infra.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("kbap.llm")
data class LlmModelProperties(
    val callTimeout: Duration = Duration.ofSeconds(30),
    val usdToKrw: Double = 1500.0,
    val openai: ModelProps = ModelProps(),
    val vision: VisionProps = VisionProps(),
    val image: ImageProps = ImageProps(),
    val embedding: EmbeddingProps = EmbeddingProps(),
) {
    data class EmbeddingProps(
        val enabled: Boolean = false,
        val model: String = "amazon.titan-embed-text-v2:0",
        val region: String = "ap-northeast-2",
        val dimension: Int = 1024,
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
        val maxRetries: Int? = null,
        // vision 은 사진 해석이라 텍스트 정제(call-timeout)보다 오래 걸린다 — 전용 타임아웃.
        val timeout: Duration = Duration.ofSeconds(60),
        // gpt-5.6-luna 기본 단가(1M 토큰당 USD) — 토큰·비용 로깅용. 모델 교체 시 이 값도 함께 옮긴다.
        val pricing: PricingProps = PricingProps(
            inputUsdPerMillionTokens = 0.2,
            outputUsdPerMillionTokens = 1.2,
        ),
    )

    data class ModelProps(
        val enabled: Boolean = false,
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val maxOutputTokens: Int? = null,
        val reasoningEffort: String? = null,
        val temperature: Double? = null,
        val maxRetries: Int? = null,
        val pricing: PricingProps = PricingProps(),
    )

    data class PricingProps(
        val inputUsdPerMillionTokens: Double = 0.0,
        val outputUsdPerMillionTokens: Double = 0.0,
    )
}
