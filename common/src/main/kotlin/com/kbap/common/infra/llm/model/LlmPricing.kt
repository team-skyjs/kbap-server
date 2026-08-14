package com.kbap.common.infra.llm.model

data class LlmPricing(
    val inputUsdPerMillionTokens: Double,
    val outputUsdPerMillionTokens: Double,
    val usdToKrw: Double,
) {
    init {
        require(inputUsdPerMillionTokens >= 0.0) { "llm.pricing.inputUsdPerMillionTokens 는 음수일 수 없습니다" }
        require(outputUsdPerMillionTokens >= 0.0) { "llm.pricing.outputUsdPerMillionTokens 는 음수일 수 없습니다" }
        require(usdToKrw >= 0.0) { "llm.pricing.usdToKrw 는 음수일 수 없습니다" }
    }

    fun costUsd(promptTokens: Long, completionTokens: Long): Double =
        (promptTokens / TOKENS_PER_MILLION) * inputUsdPerMillionTokens +
            (completionTokens / TOKENS_PER_MILLION) * outputUsdPerMillionTokens

    fun costKrw(promptTokens: Long, completionTokens: Long): Double =
        costUsd(promptTokens, completionTokens) * usdToKrw

    companion object {
        private const val TOKENS_PER_MILLION = 1_000_000.0
        const val DEFAULT_USD_TO_KRW = 1500.0
        val UNPRICED = LlmPricing(
            inputUsdPerMillionTokens = 0.0,
            outputUsdPerMillionTokens = 0.0,
            usdToKrw = DEFAULT_USD_TO_KRW,
        )
    }
}
