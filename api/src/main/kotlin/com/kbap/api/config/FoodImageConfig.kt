package com.kbap.api.config

import com.kbap.api.foodimage.FoodImageProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FoodImageConfig {
    @Bean
    fun foodImageProperties(
        @Value("\${kbap.llm.image.model:gpt-image-2}") model: String,
        @Value("\${kbap.food-image.batch-size:100}") batchSize: Int,
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
