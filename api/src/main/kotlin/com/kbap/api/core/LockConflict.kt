package com.kbap.api.core

import jakarta.persistence.LockTimeoutException
import jakarta.persistence.OptimisticLockException
import jakarta.persistence.PessimisticLockException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import java.sql.SQLException

enum class LockConflict(val severe: Boolean) {
    OPTIMISTIC(severe = false),
    DEADLOCK(severe = true),
    LOCK_WAIT(severe = true),
    ;

    companion object {
        private const val MYSQL_DEADLOCK_VICTIM = 1213

        fun of(e: Throwable?): LockConflict? {
            val chain = generateSequence(e) { it.cause }.toList()
            return when {
                chain.any { it is PessimisticLockingFailureException || it is PessimisticLockException || it is LockTimeoutException } ->
                    if (chain.any { it is SQLException && it.errorCode == MYSQL_DEADLOCK_VICTIM }) DEADLOCK else LOCK_WAIT
                chain.any { it is OptimisticLockingFailureException || it is OptimisticLockException } -> OPTIMISTIC
                else -> null
            }
        }
    }
}
