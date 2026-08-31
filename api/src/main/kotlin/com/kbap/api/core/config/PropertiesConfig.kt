package com.kbap.api.core.config

import com.kbap.api.food.FoodImageProperties
import com.kbap.api.image.ImageUploadProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class PropertiesConfig {
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

    @Bean
    fun imageUploadProperties(
        @Value("\${kbap.upload.allowed-content-types}") allowedContentTypes: Set<String>,
        @Value("\${kbap.upload.max-bytes}") maxBytes: Long,
        @Value("\${kbap.upload.upload-ttl}") uploadTtl: Duration,
        @Value("\${kbap.storage.public-base-url:}") publicBaseUrl: String,
        @Value("\${kbap.storage.key-prefix:}") keyPrefix: String,
    ): ImageUploadProperties =
        ImageUploadProperties(
            allowedContentTypes = allowedContentTypes,
            maxBytes = maxBytes,
            uploadTtl = uploadTtl,
            publicBaseUrl = publicBaseUrl,
            keyPrefix = keyPrefix,
        )
}
