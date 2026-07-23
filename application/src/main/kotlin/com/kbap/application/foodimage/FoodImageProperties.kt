package com.kbap.application.foodimage

// 이미지 배치 운영 파라미터(KB-226). model 은 infra 어댑터(kbap.llm.image.model)와 같은 yml 키에서 바인딩된다.
data class FoodImageProperties(
    val model: String,
    val batchSize: Int,
    val prompt: String,
    val promptVersion: String,
    val inputUsdPerMillionTokens: Double,
    val outputUsdPerMillionTokens: Double,
    val usdToKrw: Double,
) {
    fun promptFor(koreanName: String): String = prompt.format(koreanName)
}
