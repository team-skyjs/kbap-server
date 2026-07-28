package com.kbap.api.upload

import com.kbap.common.application.upload.dto.PresignedUpload
import java.time.Instant

data class UploadUrlResponse(
    val uploadUrl: String,
    val method: String,
    val requiredHeaders: Map<String, String>,
    val publicUrl: String,
    val objectKey: String,
    val expiresAt: Instant,
) {
    companion object {
        fun from(upload: PresignedUpload): UploadUrlResponse =
            UploadUrlResponse(
                uploadUrl = upload.uploadUrl,
                method = "PUT",
                requiredHeaders = upload.requiredHeaders,
                publicUrl = upload.publicUrl,
                objectKey = upload.objectKey,
                expiresAt = upload.expiresAt,
            )
    }
}
