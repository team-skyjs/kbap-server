package com.kbap.api.admin

import com.kbap.api.appversion.AppVersionResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.AdminAuditLogFilter
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.appversion.AppVersionJpaRepository
import com.kbap.common.domain.appversion.model.AppVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminAppVersionService(
    private val appVersionRepository: AppVersionJpaRepository,
    private val auditRecorder: AdminAuditRecorder,
    private val adminAuditLogService: AdminAuditLogService,
) {
    @Transactional(readOnly = true)
    fun getAppVersion(): AppVersionResponse = AppVersionResponse.from(currentAppVersion())

    @Transactional(readOnly = true)
    fun getHistory(page: Int, size: Int): AdminAuditLogPageResponse =
        adminAuditLogService.getAuditLogPage(AdminAuditLogFilter(targetType = AdminAuditTargetType.APP_VERSION), page, size)

    @Transactional
    fun updateAppVersion(adminId: Long, request: AdminAppVersionUpdateRequest): AppVersionResponse {
        val appVersion = currentAppVersion()
        val before = snapshot(appVersion)
        appVersion.update(
            minSupportedVersion = request.minSupportedVersion,
            latestVersion = request.latestVersion,
            iosStoreUrl = request.iosStoreUrl,
            aosStoreUrl = request.aosStoreUrl,
        )
        auditRecorder.record(adminId, AdminAuditAction.APP_VERSION_UPDATE, AdminAuditTargetType.APP_VERSION, appVersion.id, before, snapshot(appVersion))
        return AppVersionResponse.from(appVersion)
    }

    private fun snapshot(appVersion: AppVersion): Map<String, Any?> = mapOf(
        "minSupportedVersion" to appVersion.minSupportedVersion,
        "latestVersion" to appVersion.latestVersion,
        "iosStoreUrl" to appVersion.iosStoreUrl,
        "aosStoreUrl" to appVersion.aosStoreUrl,
    )

    private fun currentAppVersion(): AppVersion =
        appVersionRepository.findTopByOrderByIdAsc()
            ?: throw BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)
}
