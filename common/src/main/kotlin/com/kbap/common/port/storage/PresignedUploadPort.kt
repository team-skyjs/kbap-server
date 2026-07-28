package com.kbap.common.port.storage

import com.kbap.common.port.storage.PresignedUpload
import java.time.Duration

interface PresignedUploadPort {
    fun issue(key: String, contentType: String, contentLength: Long, ttl: Duration): PresignedUpload
}
