package com.kbap.common.port.feedback

import java.time.Duration

interface FeedbackQuotaStore {
    fun tryAcquire(installationId: String, requestId: String, limit: Int, window: Duration): Boolean

    fun release(installationId: String, requestId: String)
}
