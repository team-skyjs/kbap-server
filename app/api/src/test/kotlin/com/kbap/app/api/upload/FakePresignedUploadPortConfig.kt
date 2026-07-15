package com.kbap.app.api.upload

import com.kbap.application.upload.PresignedUploadPort
import com.kbap.application.upload.dto.PresignedUpload
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Duration
import java.time.Instant

@TestConfiguration
class FakePresignedUploadPortConfig {
    @Bean
    @Primary
    fun fakePresignedUploadPort(): PresignedUploadPort =
        object : PresignedUploadPort {
            override fun issue(key: String, contentType: String, contentLength: Long, ttl: Duration): PresignedUpload =
                PresignedUpload(
                    uploadUrl = "https://s3.example/$key?sig=test",
                    requiredHeaders = mapOf("Content-Type" to contentType, "Content-Length" to contentLength.toString()),
                    publicUrl = "https://cdn.test/$key",
                    objectKey = key,
                    expiresAt = Instant.parse("2026-07-15T00:05:00Z"),
                )
        }
}
