package com.kbap.infra.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("kbap.llm")
data class LlmModelProperties(
    val callTimeout: Duration = Duration.ofSeconds(30),
    val usdToKrw: Double = 1500.0,
    val openai: ModelProps = ModelProps(),
    val upstage: ModelProps = ModelProps(),
    val gemini: ModelProps = ModelProps(),
    val vision: VisionProps = VisionProps(),
    val image: ImageProps = ImageProps(),
    val avoidance: AvoidanceProps = AvoidanceProps(),
) {
    // 기피성분 조사 전용 OpenAI 오버라이드 — null 필드는 kbap.llm.openai 값을 상속한다.
    data class AvoidanceProps(
        val minAgreement: Int = 2,
        val model: String? = null,
        val maxOutputTokens: Int? = null,
        val reasoningEffort: String? = null,
        val pricing: PricingProps? = null,
    )

    // 음식 사진 생성 전용
    data class ImageProps(
        val enabled: Boolean = false,
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val size: String? = null,
        val quality: String? = null,
    )

    // 메뉴판 사진 스캔 전용
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
        // gpt-4o-mini 기본 단가(1M 토큰당 USD) — 토큰·비용 로깅용.
        val pricing: PricingProps = PricingProps(
            inputUsdPerMillionTokens = 0.15,
            outputUsdPerMillionTokens = 0.60,
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
