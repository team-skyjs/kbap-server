package com.kbap.batch.config

import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.infra.storage.S3StorageObjectStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(prefix = "kbap.storage", name = ["enabled"], havingValue = "true")
class BatchStorageConfig {
    @Bean
    fun storageObjectStore(
        @Value("\${kbap.storage.region}") region: String,
        @Value("\${kbap.storage.bucket}") bucket: String,
    ): StorageObjectStore = S3StorageObjectStore.create(region, bucket)
}
