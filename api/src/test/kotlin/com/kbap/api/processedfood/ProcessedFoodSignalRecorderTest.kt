package com.kbap.api.processedfood

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus

class ProcessedFoodSignalRecorderTest : BehaviorSpec({
    val noopJdbc = object : JdbcTemplate() {
        override fun update(sql: String, vararg args: Any?): Int = 1
    }

    given("가공식품 신호 기록기") {
        `when`("트랜잭션을 열지 못하면(커넥션 획득 실패)") {
            then("예외를 밖으로 던지지 않는다 — 확정·상세 응답은 신호 적재와 무관하다") {
                val failingBegin = object : PlatformTransactionManager {
                    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = throw CannotCreateTransactionException("no connection")
                    override fun commit(status: TransactionStatus) = Unit
                    override fun rollback(status: TransactionStatus) = Unit
                }
                shouldNotThrowAny { ProcessedFoodSignalRecorder(noopJdbc, failingBegin).record(1, 1, ProcessedFoodSignalType.SCAN_MATCH) }
            }
        }

        `when`("커밋에서 실패하면") {
            then("예외를 밖으로 던지지 않는다") {
                val failingCommit = object : PlatformTransactionManager {
                    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
                    override fun commit(status: TransactionStatus) = throw TransactionSystemException("commit failed")
                    override fun rollback(status: TransactionStatus) = Unit
                }
                shouldNotThrowAny { ProcessedFoodSignalRecorder(noopJdbc, failingCommit).record(1, 1, ProcessedFoodSignalType.VIEW) }
            }
        }
    }
})
