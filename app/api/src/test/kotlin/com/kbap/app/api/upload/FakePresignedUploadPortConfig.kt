package com.kbap.app.api.upload

import com.kbap.common.application.upload.PresignedUploadPort
import com.kbap.common.application.upload.dto.PresignedUpload
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant

// 전 app:api 통합 테스트가 공유하는 페이크 — ImageUploadApplicationService 가 PresignedUploadPort 빈을
// 요구하므로 항상 스캔되는 @Configuration 으로 제공한다(실 StorageConfig 는 kbap.storage.enabled 로 꺼져 있어 충돌 없음).
@Configuration
class FakePresignedUploadPortConfig {
    @Bean
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
