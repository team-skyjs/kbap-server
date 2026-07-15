package com.kbap.infra.storage.config

import com.kbap.application.upload.PresignedUploadPort
import com.kbap.application.upload.UnavailablePresignedUploadPort
import com.kbap.infra.storage.S3PresignedUploadPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.presigner.S3Presigner

@Configuration
class StorageConfiguration {
    @Bean
    @ConditionalOnProperty("kbap.storage.bucket")
    fun s3Presigner(@Value("\${kbap.storage.region}") region: String): S3Presigner =
        S3Presigner.builder().region(Region.of(region)).build()

    @Bean
    @ConditionalOnProperty("kbap.storage.bucket")
    fun s3PresignedUploadPort(
        presigner: S3Presigner,
        @Value("\${kbap.storage.bucket}") bucket: String,
        @Value("\${kbap.storage.public-base-url}") publicBaseUrl: String,
    ): PresignedUploadPort = S3PresignedUploadPort(presigner, bucket, publicBaseUrl)

    @Bean
    @ConditionalOnMissingBean(PresignedUploadPort::class)
    fun unavailablePresignedUploadPort(): PresignedUploadPort = UnavailablePresignedUploadPort
}
