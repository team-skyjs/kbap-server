package com.kbap.app.api.config

import com.kbap.application.foodimage.FoodImageProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 이미지 배치 운영 파라미터(KB-226). model 은 infra 어댑터(LlmModelProperties.image)와 같은 yml 키 —
// 값의 출처를 kbap.llm.image.model 하나로 유지한다.
@Configuration
class FoodImageConfig {
    @Bean
    fun foodImageProperties(
        @Value("\${kbap.llm.image.model:gpt-image-2}") model: String,
        @Value("\${kbap.food-image.batch-size:10}") batchSize: Int,
        @Value("\${kbap.llm.image.pricing.input-usd-per-million-tokens:0}") inputUsdPerMillionTokens: Double,
        @Value("\${kbap.llm.image.pricing.output-usd-per-million-tokens:0}") outputUsdPerMillionTokens: Double,
        @Value("\${kbap.llm.usd-to-krw:1500}") usdToKrw: Double,
    ): FoodImageProperties =
        FoodImageProperties(
            model = model,
            batchSize = batchSize,
            inputUsdPerMillionTokens = inputUsdPerMillionTokens,
            outputUsdPerMillionTokens = outputUsdPerMillionTokens,
            usdToKrw = usdToKrw,
        )
}
