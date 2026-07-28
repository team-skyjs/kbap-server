package com.kbap.api.core.config

import com.kbap.common.port.storage.PresignedUploadPort
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.infra.storage.S3PresignedUploadAdapter
import com.kbap.infra.storage.S3StorageObjectStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 실 S3 접근은 dev/prod 등 kbap.storage.enabled=true 프로파일에서만 조립한다.
// 미구성(local·테스트)에서는 빈이 없고, 테스트는 StorageObjectStore·PresignedUploadPort 페이크로 검증한다.
@Configuration
@ConditionalOnProperty(prefix = "kbap.storage", name = ["enabled"], havingValue = "true")
class StorageConfig {
    @Bean
    fun storageObjectStore(
        @Value("\${kbap.storage.region}") region: String,
        @Value("\${kbap.storage.bucket}") bucket: String,
    ): StorageObjectStore = S3StorageObjectStore.create(region, bucket)

    @Bean
    fun presignedUploadPort(
        @Value("\${kbap.storage.region}") region: String,
        @Value("\${kbap.storage.bucket}") bucket: String,
        @Value("\${kbap.storage.public-base-url}") publicBaseUrl: String,
    ): PresignedUploadPort = S3PresignedUploadAdapter.create(region, bucket, publicBaseUrl)
}
