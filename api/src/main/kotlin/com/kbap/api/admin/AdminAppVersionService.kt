package com.kbap.api.admin

import com.kbap.api.appversion.AppVersionResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.appversion.AppVersionJpaRepository
import com.kbap.common.domain.appversion.model.AppVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminAppVersionService(
    private val appVersionRepository: AppVersionJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getAppVersion(): AppVersionResponse = AppVersionResponse.from(currentAppVersion())

    @Transactional
    fun updateAppVersion(request: AdminAppVersionUpdateRequest): AppVersionResponse {
        val appVersion = currentAppVersion()
        appVersion.update(
            minSupportedVersion = request.minSupportedVersion,
            latestVersion = request.latestVersion,
            iosStoreUrl = request.iosStoreUrl,
            aosStoreUrl = request.aosStoreUrl,
        )
        return AppVersionResponse.from(appVersion)
    }

    private fun currentAppVersion(): AppVersion =
        appVersionRepository.findTopByOrderByIdAsc()
            ?: throw BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)
}
