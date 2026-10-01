package com.kbap.api.core

import jakarta.persistence.LockTimeoutException
import jakarta.persistence.OptimisticLockException
import jakarta.persistence.PessimisticLockException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import java.sql.SQLException

object LockConflicts {
    private const val MYSQL_DEADLOCK_VICTIM = 1213

    fun isLockConflict(e: Throwable?): Boolean = causeChain(e).any { isOptimistic(it) || isPessimistic(it) }

    fun isLockHeldTooLong(e: Throwable): Boolean {
        val chain = causeChain(e).toList()
        return chain.any(::isPessimistic) && chain.none { it is SQLException && it.errorCode == MYSQL_DEADLOCK_VICTIM }
    }

    private fun isOptimistic(e: Throwable): Boolean = e is OptimisticLockingFailureException || e is OptimisticLockException

    private fun isPessimistic(e: Throwable): Boolean =
        e is PessimisticLockingFailureException || e is PessimisticLockException || e is LockTimeoutException

    private fun causeChain(e: Throwable?): Sequence<Throwable> = generateSequence(e) { it.cause }
}
