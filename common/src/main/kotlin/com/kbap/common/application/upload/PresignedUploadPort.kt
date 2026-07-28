package com.kbap.common.application.upload

import com.kbap.common.application.upload.dto.PresignedUpload
import java.time.Duration

interface PresignedUploadPort {
    fun issue(key: String, contentType: String, contentLength: Long, ttl: Duration): PresignedUpload
}
