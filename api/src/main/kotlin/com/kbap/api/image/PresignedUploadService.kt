package com.kbap.api.image

import com.kbap.common.port.storage.PresignedUploadPort
import com.kbap.common.port.storage.PresignedUpload
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.model.UploadPurpose
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Service
class PresignedUploadService(
    private val properties: ImageUploadProperties,
    private val port: PresignedUploadPort,
    private val guestUploadQuota: GuestUploadQuota,
) {
    fun issueUploadUrl(input: ImageUploadInput): PresignedUpload {
        val purpose = UploadPurpose.from(input.purpose)
            ?: throw BusinessException(ErrorCode.UNSUPPORTED_UPLOAD_PURPOSE)
        if (input.memberId == null) {
            if (purpose != UploadPurpose.FEEDBACK) throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
            guestUploadQuota.verify(input.installationId)
        }
        if (input.contentType !in properties.allowedContentTypes) {
            throw BusinessException(ErrorCode.UNSUPPORTED_IMAGE_CONTENT_TYPE)
        }
        if (input.contentLength > properties.maxBytes) {
            throw BusinessException(ErrorCode.IMAGE_TOO_LARGE)
        }
        val key = issuedKey(properties.keyPrefix, purpose, ownerKeyOf(input.memberId, input.installationId), input.contentType)
        return port.issue(key, input.contentType, input.contentLength, properties.uploadTtl)
    }

    companion object {
        private val ISSUED_KEY = Regex("""^images/([a-z]+)/\d{4}/\d{2}/(\d+|g[0-9a-f]{12})_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]{2,5}$""")

        fun ownerKeyOf(memberId: Long?, installationId: String?): String =
            memberId?.toString() ?: guestOwnerKey(installationId ?: throw BusinessException(ErrorCode.INVALID_REQUEST))

        fun guestOwnerKey(installationId: String): String =
            "g" + MessageDigest.getInstance("SHA-256").digest(installationId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .substring(0, 12)

        fun issuedKey(keyPrefix: String, purpose: UploadPurpose, ownerKey: String, contentType: String): String {
            val date = LocalDate.now(ZoneOffset.UTC)
            val prefix = keyPrefix.trim('/')
            val baseKey = "images/%s/%04d/%02d/%s_%s.%s".format(
                purpose.prefix,
                date.year,
                date.monthValue,
                ownerKey,
                UUID.randomUUID(),
                extensionOf(contentType),
            )
            return if (prefix.isEmpty()) baseKey else "$prefix/$baseKey"
        }

        fun issuedPurposeOf(path: String, keyPrefix: String, ownerKey: String): UploadPurpose? {
            val prefix = keyPrefix.trim('/')
            val relative = if (prefix.isEmpty()) path else path.removePrefix("$prefix/").takeIf { it != path } ?: return null
            val match = ISSUED_KEY.matchEntire(relative) ?: return null
            if (match.groupValues[2] != ownerKey) return null
            return UploadPurpose.entries.firstOrNull { it.prefix == match.groupValues[1] }
        }

        private fun extensionOf(contentType: String): String {
            val subtype = contentType.substringAfterLast('/').lowercase()
            return if (subtype == "jpeg") "jpg" else subtype
        }
    }
}
