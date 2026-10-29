package com.kbap.api.processedfood

import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.scan.ScanOperationJpaRepository
import com.kbap.common.port.llm.NameCandidate
import com.kbap.common.port.llm.NameDecisionFailure
import com.kbap.common.port.llm.NameDecisionImageUnavailable
import com.kbap.common.port.llm.NameDecisionRefused
import com.kbap.common.port.llm.NameDecisionUnparsable
import com.kbap.common.port.llm.NameDecisionUsage
import com.kbap.common.port.llm.ProcessedFoodNameDecider
import com.kbap.common.util.ImageUrls
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@Service
class ProcessedFoodNameDecisionService(
    deciderProvider: ObjectProvider<ProcessedFoodNameDecider>,
    private val operations: ScanOperationJpaRepository,
    private val redis: StringRedisTemplate,
    private val eventPublisher: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
    @Value("\${kbap.processed-food.name-scan.decision.model:claude-haiku-5-5}") private val model: String,
    @Value("\${kbap.processed-food.name-scan.decision.daily-budget-krw:2000}") var dailyBudgetKrw: Double,
    @Value("\${kbap.processed-food.name-scan.decision.estimated-krw-per-call:0.8}") private val estimatedKrwPerCall: Double,
    @Value("\${kbap.processed-food.name-scan.decision.input-usd-per-million-tokens:0.10}") inputUsdPerMillionTokens: Double,
    @Value("\${kbap.processed-food.name-scan.decision.output-usd-per-million-tokens:0.50}") outputUsdPerMillionTokens: Double,
    @Value("\${kbap.llm.usd-to-krw:1500}") private val usdToKrw: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val inputUsdPerToken = inputUsdPerMillionTokens / 1_000_000
    private val outputUsdPerToken = outputUsdPerMillionTokens / 1_000_000
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val running = AtomicInteger()
    private val background = ThreadPoolExecutor(
        THREADS, THREADS, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(QUEUE),
        { Thread(it, "pf-name-decision").apply { isDaemon = true } },
    )

    var decider: ProcessedFoodNameDecider? = deciderProvider.getIfAvailable().also {
        if (it == null) log.info("가공식품 이름 스캔 Haiku 판별 꺼짐 — 플래그 off 또는 ANTHROPIC_API_KEY 없음, 호출 0")
    }

    fun isEnabled(): Boolean = decider != null

    fun requestAfterCommit(operationId: Long, imagePath: String, candidates: List<ProcessedFoodProduct>) {
        val offered = candidates.take(MAX_CANDIDATES).map { Offered(it.reportNo, NameCandidate(it.productName, it.displayMaker())) }
        submitAfterCommit(operationId) { decide(imagePath, offered) }
    }

    fun recordSkipped(operationId: Long, reason: String) {
        submitAfterCommit(operationId) { mapOf("model" to model, "at" to Instant.now().toString(), "error" to reason) }
    }

    private fun submitAfterCommit(operationId: Long, decision: () -> Map<String, Any?>) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return submit(operationId, decision)
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = submit(operationId, decision)
            },
        )
    }

    fun awaitIdle() {
        val deadline = System.nanoTime() + IDLE_WAIT_NANOS
        while ((running.get() > 0 || background.queue.isNotEmpty()) && System.nanoTime() < deadline) Thread.sleep(IDLE_POLL_MILLIS)
    }

    private data class Offered(val reportNo: String, val candidate: NameCandidate)

    private fun submit(operationId: Long, decision: () -> Map<String, Any?>) {
        running.incrementAndGet()
        try {
            background.execute {
                try {
                    store(operationId, decision())
                } finally {
                    running.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            running.decrementAndGet()
            log.warn("가공식품 이름 스캔 판별 대기열이 가득 차 건너뜁니다 operationId={}", operationId)
        }
    }

    private fun decide(imagePath: String, offered: List<Offered>): Map<String, Any?> {
        val at = Instant.now().toString()
        val decider = decider ?: return mapOf("model" to model, "at" to at, "error" to "disabled")
        if (!withinBudget()) return mapOf("model" to model, "at" to at, "error" to "budget")
        val imageUrl = ImageUrls.resolve(imagePublicBaseUrl, imagePath) ?: return mapOf("model" to model, "at" to at, "error" to "image")
        val started = System.nanoTime()
        return try {
            val decision = decider.decide(imageUrl, offered.map { it.candidate })
            val cost = recordCost(NameDecisionUsage(decision.model, decision.inputTokens, decision.outputTokens))
            val chosen = decision.choice?.let { offered.getOrNull(it - 1) }
            val outside = decision.choice != null && chosen == null
            mapOf(
                "model" to decision.model,
                "choice" to chosen?.reportNo,
                "confidence" to decision.confidence,
                "readName" to decision.readName,
                "latencyMs" to elapsedMillis(started),
                "at" to at,
            ).plus(cost).let { if (outside) it - "choice" + ("error" to "outside") else it }
        } catch (e: Exception) {
            val reason = reasonOf(e)
            log.warn("가공식품 이름 스캔 판별 실패 — 응답에는 영향 없음, reason={}", reason, e)
            val usage = (e as? NameDecisionFailure)?.usage
            mapOf("model" to (usage?.model ?: model), "latencyMs" to elapsedMillis(started), "at" to at, "error" to reason) +
                usage?.let(::recordCost).orEmpty()
        }
    }

    private fun recordCost(usage: NameDecisionUsage): Map<String, Any?> {
        val costUsd = usage.inputTokens * inputUsdPerToken + usage.outputTokens * outputUsdPerToken
        val costKrw = BigDecimal.valueOf(costUsd * usdToKrw).setScale(2, RoundingMode.HALF_UP)
        runCatching {
            eventPublisher.publishEvent(
                LlmCallCostIncurred(
                    modelName = usage.model,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    costUsd = BigDecimal.valueOf(costUsd).setScale(6, RoundingMode.HALF_UP),
                    costKrw = costKrw,
                ),
            )
        }.onFailure { log.warn("이름 스캔 판별 비용 이벤트 발행 실패", it) }
        return mapOf("inputTokens" to usage.inputTokens, "outputTokens" to usage.outputTokens, "costKrw" to costKrw.toDouble())
    }

    private fun store(operationId: Long, decision: Map<String, Any?>) {
        runCatching {
            writeTransaction.executeWithoutResult {
                operations.findByIdForUpdate(operationId)?.let { it.requestDocument = it.requestDocument + ("decision" to decision) }
            }
        }.onFailure { log.warn("가공식품 이름 스캔 판별 저장 실패 operationId={}", operationId, it) }
    }

    private fun withinBudget(): Boolean = try {
        val key = BUDGET_KEY_PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val attempts = redis.opsForValue().increment(key) ?: 0L
        if (attempts == 1L) redis.expire(key, BUDGET_KEY_TTL)
        attempts > 0 && attempts * estimatedKrwPerCall <= dailyBudgetKrw
    } catch (e: RuntimeException) {
        log.warn("가공식품 이름 스캔 판별 일일 상한 계수 실패 — 부르지 않는다", e)
        false
    }

    private fun elapsedMillis(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    companion object {
        const val MAX_CANDIDATES = 20
        private const val THREADS = 2
        private const val QUEUE = 100
        private const val BUDGET_KEY_PREFIX = "pf-name-decision:"
        private val BUDGET_KEY_TTL = Duration.ofDays(2)
        private const val IDLE_WAIT_NANOS = 10_000_000_000L
        private const val IDLE_POLL_MILLIS = 50L

        fun reasonOf(e: Throwable): String = when {
            e is NameDecisionRefused -> "refusal"
            e is NameDecisionImageUnavailable -> "image"
            e is NameDecisionUnparsable -> "parse"
            generateSequence(e) { it.cause }.any { it is InterruptedIOException || it is TimeoutException } -> "timeout"
            else -> "error"
        }
    }
}
