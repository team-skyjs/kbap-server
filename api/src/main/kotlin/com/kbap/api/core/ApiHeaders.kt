package com.kbap.api.core

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode

object ApiHeaders {
    const val API_VERSION = "X-API-Version"
    const val INSTALLATION_ID = "X-Installation-Id"

    private const val INSTALLATION_ID_MAX_LENGTH = 36

    fun validInstallationId(raw: String): String {
        if (raw.isBlank() || raw.length > INSTALLATION_ID_MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        return raw
    }
}
