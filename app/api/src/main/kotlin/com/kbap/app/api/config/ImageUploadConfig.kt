package com.kbap.app.api.config

import com.kbap.application.upload.ImageUploadProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class ImageUploadConfig {
    @Bean
    fun imageUploadProperties(
        @Value("\${kbap.upload.allowed-content-types}") allowedContentTypes: Set<String>,
        @Value("\${kbap.upload.max-bytes}") maxBytes: Long,
        @Value("\${kbap.upload.upload-ttl}") uploadTtl: Duration,
        @Value("\${kbap.storage.public-base-url:}") publicBaseUrl: String,
    ): ImageUploadProperties =
        ImageUploadProperties(
            allowedContentTypes = allowedContentTypes,
            maxBytes = maxBytes,
            uploadTtl = uploadTtl,
            publicBaseUrl = publicBaseUrl,
        )
}
