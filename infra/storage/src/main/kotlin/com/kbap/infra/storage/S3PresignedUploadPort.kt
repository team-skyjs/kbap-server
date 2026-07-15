package com.kbap.infra.storage

import com.kbap.application.upload.PresignedUploadPort
import com.kbap.application.upload.dto.PresignedUpload
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration

class S3PresignedUploadPort(
    private val presigner: S3Presigner,
    private val bucket: String,
    private val publicBaseUrl: String,
) : PresignedUploadPort {
    override fun issue(key: String, contentType: String, contentLength: Long, ttl: Duration): PresignedUpload {
        val putObjectRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .build()
        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .putObjectRequest(putObjectRequest)
            .build()
        val presigned = presigner.presignPutObject(presignRequest)
        return PresignedUpload(
            uploadUrl = presigned.url().toString(),
            requiredHeaders = mapOf(
                "Content-Type" to contentType,
                "Content-Length" to contentLength.toString(),
            ),
            publicUrl = "${publicBaseUrl.trimEnd('/')}/$key",
            objectKey = key,
            expiresAt = presigned.expiration(),
        )
    }
}
