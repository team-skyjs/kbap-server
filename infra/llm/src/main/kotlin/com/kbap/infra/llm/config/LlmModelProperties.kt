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
) {
    // 메뉴판 사진 스캔 전용(KB-138) — 배치 채점 모델(openai)과 독립 구성.
    // imageBaseUrl(CDN 도메인) + 오브젝트 path 를 조합해 모델이 fetch 할 전체 URL 을 만든다.
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
