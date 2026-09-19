package com.kbap.api.image

import com.kbap.api.core.ApiHeaders
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

fun interface GuestUploadQuota {
    fun verify(rawInstallationId: String?): String

    companion object {
        const val DAILY_LIMIT = 10
    }
}

@Component
class DailyGuestUploadQuota(
    private val uploadedImageRepository: UploadedImageJpaRepository,
) : GuestUploadQuota {
    @Transactional(readOnly = true)
    override fun verify(rawInstallationId: String?): String {
        val installationId = rawInstallationId?.let(ApiHeaders::validInstallationId)
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val since = LocalDateTime.now().minusDays(1)
        if (uploadedImageRepository.countByInstallationIdAndCreatedAtAfter(installationId, since) >=
            GuestUploadQuota.DAILY_LIMIT
        ) {
            throw BusinessException(ErrorCode.IMAGE_UPLOAD_RATE_LIMITED)
        }
        return installationId
    }
}
