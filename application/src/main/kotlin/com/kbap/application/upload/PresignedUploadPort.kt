package com.kbap.application.upload

import com.kbap.application.upload.dto.PresignedUpload
import java.time.Duration

interface PresignedUploadPort {
    fun issue(key: String, contentType: String, contentLength: Long, ttl: Duration): PresignedUpload
}
