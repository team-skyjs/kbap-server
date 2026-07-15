package com.kbap.application.upload

import com.kbap.application.upload.dto.ImageUploadInput
import com.kbap.application.upload.dto.PresignedUpload
import com.kbap.application.upload.dto.UploadPurpose
import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Service
class ImageUploadApplicationService(
    private val properties: ImageUploadProperties,
    private val port: PresignedUploadPort,
) {
    fun issueUploadUrl(input: ImageUploadInput): PresignedUpload {
        val purpose = UploadPurpose.from(input.purpose)
            ?: throw BusinessException(ErrorCode.UNSUPPORTED_UPLOAD_PURPOSE)
        if (input.contentType !in properties.allowedContentTypes) {
            throw BusinessException(ErrorCode.UNSUPPORTED_IMAGE_CONTENT_TYPE)
        }
        if (input.contentLength > properties.maxBytes) {
            throw BusinessException(ErrorCode.IMAGE_TOO_LARGE)
        }
        val key = objectKey(purpose, input.memberId, input.contentType)
        return port.issue(key, input.contentType, input.contentLength, properties.uploadTtl)
    }

    private fun objectKey(purpose: UploadPurpose, memberId: Long, contentType: String): String {
        val date = LocalDate.now(ZoneOffset.UTC)
        return "%s/%04d/%02d/%02d/%d/%s.%s".format(
            purpose.prefix,
            date.year,
            date.monthValue,
            date.dayOfMonth,
            memberId,
            UUID.randomUUID(),
            extensionOf(contentType),
        )
    }

    private fun extensionOf(contentType: String): String {
        val subtype = contentType.substringAfterLast('/').lowercase()
        return if (subtype == "jpeg") "jpg" else subtype
    }
}
