package com.kbap.api.image

import com.kbap.api.core.ApiHeaders
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.port.quota.InstallationQuotaStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

fun interface GuestUploadQuota {
    fun verify(rawInstallationId: String?): String

    fun <T> consume(installationId: String, register: () -> T): T = register()

    companion object {
        const val DAILY_LIMIT = 10
        const val QUOTA_SCOPE = "upload"
        val QUOTA_WINDOW: Duration = Duration.ofDays(1)
    }
}

@Component
class DailyGuestUploadQuota(
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val quotaStore: InstallationQuotaStore,
) : GuestUploadQuota {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun verify(rawInstallationId: String?): String {
        val installationId = rawInstallationId?.let(ApiHeaders::validInstallationId)
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        verifyRecorded(installationId)
        return installationId
    }

    override fun <T> consume(installationId: String, register: () -> T): T {
        val requestId = UUID.randomUUID().toString()
        val holdsQuota = acquire(installationId, requestId, recordedAtMillis(installationId))
        return try {
            register()
        } catch (e: Throwable) {
            if (holdsQuota) release(installationId, requestId)
            throw e
        }
    }

    private fun verifyRecorded(installationId: String) {
        val since = LocalDateTime.now().minus(GuestUploadQuota.QUOTA_WINDOW)
        if (uploadedImageRepository.countByInstallationIdAndCreatedAtAfter(installationId, since) >=
            GuestUploadQuota.DAILY_LIMIT
        ) {
            throw BusinessException(ErrorCode.IMAGE_UPLOAD_RATE_LIMITED)
        }
    }

    private fun recordedAtMillis(installationId: String): List<Long> {
        val since = LocalDateTime.now().minus(GuestUploadQuota.QUOTA_WINDOW)
        val recorded = uploadedImageRepository.findCreatedAtsByInstallationIdSince(installationId, since)
        if (recorded.size >= GuestUploadQuota.DAILY_LIMIT) throw BusinessException(ErrorCode.IMAGE_UPLOAD_RATE_LIMITED)
        return recorded.map { it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    }

    private fun acquire(installationId: String, requestId: String, recordedAtMillis: List<Long>): Boolean {
        val acquired = try {
            quotaStore.tryAcquire(
                GuestUploadQuota.QUOTA_SCOPE,
                installationId,
                requestId,
                GuestUploadQuota.DAILY_LIMIT,
                GuestUploadQuota.QUOTA_WINDOW,
                recordedAtMillis,
            )
        } catch (e: RuntimeException) {
            log.warn("업로드 한도 카운터(Redis)를 쓸 수 없어 DB 건수로만 판정한다 installationId={}", installationId, e)
            return false
        }
        if (!acquired) throw BusinessException(ErrorCode.IMAGE_UPLOAD_RATE_LIMITED)
        return true
    }

    private fun release(installationId: String, requestId: String) {
        runCatching { quotaStore.release(GuestUploadQuota.QUOTA_SCOPE, installationId, requestId) }
            .onFailure { log.warn("업로드 한도 카운터 반납 실패 — 24시간 뒤 만료된다 installationId={}", installationId, it) }
    }
}
