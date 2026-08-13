package com.kbap.api.appversion

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.appversion.AppVersionJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AppVersionService(
    private val appVersionRepository: AppVersionJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getAppVersion(): AppVersionResponse {
        val appVersion = appVersionRepository.findTopByOrderByIdAsc()
            ?: throw BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)
        return AppVersionResponse.from(appVersion)
    }
}
