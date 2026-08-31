package com.kbap.api.image

import com.kbap.common.port.storage.PresignedUpload
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
