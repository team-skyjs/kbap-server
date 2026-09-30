package com.kbap.common.port.quota

import java.time.Duration

interface InstallationQuotaStore {
    fun tryAcquire(scope: String, installationId: String, requestId: String, limit: Int, window: Duration): Boolean

    fun release(scope: String, installationId: String, requestId: String)
}
