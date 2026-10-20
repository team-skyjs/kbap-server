package com.kbap.api.curation

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class CurationAccess(
    private val admins: AdminAccountJpaRepository,
    @Value("\${kbap.curation.environment:disabled}") val environment: String,
    @Value("\${kbap.storage.public-base-url:}") private val publicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun verifyAdmin(adminId: Long, target: String? = null) {
        if (!admins.existsById(adminId)) throw BusinessException(ErrorCode.ADMIN_FORBIDDEN)
        if (environment !in setOf("dev", "prod")) throw BusinessException(ErrorCode.CURATION_UNAVAILABLE)
        if (target != null) {
            if (target !in setOf("dev", "prod")) throw BusinessException(ErrorCode.INVALID_REQUEST)
            if (target != environment) throw BusinessException(ErrorCode.CURATION_ENVIRONMENT_MISMATCH)
        }
    }
    fun imageUrl(key: String): String {
        if (publicBaseUrl.isBlank() || !key.startsWith("curation/$environment/final/")) throw BusinessException(ErrorCode.CURATION_UNAVAILABLE)
        return "${publicBaseUrl.trimEnd('/')}/$key"
    }
}
