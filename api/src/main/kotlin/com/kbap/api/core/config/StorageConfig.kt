package com.kbap.api.core.config

import com.kbap.common.port.storage.ImmutableObjectStore
import com.kbap.api.infra.storage.S3ImmutableObjectStore
import com.kbap.common.port.storage.PresignedUploadPort
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.api.infra.storage.S3PresignedUploadAdapter
import com.kbap.api.infra.storage.S3StorageObjectStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(prefix = "kbap.storage", name = ["enabled"], havingValue = "true")
class StorageConfig {
    @Bean
    fun immutableObjectStore(
        @Value("\${kbap.storage.region}") region: String,
        @Value("\${kbap.storage.bucket}") bucket: String,
    ): ImmutableObjectStore = S3ImmutableObjectStore.create(region, bucket)

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
