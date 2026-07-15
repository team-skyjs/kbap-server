package com.kbap.application.upload

import com.kbap.application.upload.dto.PresignedUpload
import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import java.time.Duration

object UnavailablePresignedUploadPort : PresignedUploadPort {
    override fun issue(key: String, contentType: String, contentLength: Long, ttl: Duration): PresignedUpload =
        throw BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)
}
