package com.kbap.common.port.auth

interface LoginAttemptStore {
    fun isLocked(key: String): Boolean

    fun recordFailure(key: String): Int

    fun reset(key: String)
}
