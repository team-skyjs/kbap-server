package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.port.llm.IngredientDecider
import com.kbap.common.port.llm.IngredientDecisionFood
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

@Service
class FoodIngredientRetroService(
    deciderProvider: ObjectProvider<IngredientDecider>,
    private val foods: FoodJpaRepository,
    private val foodIngredients: FoodIngredientJdbcRepository,
    private val redis: StringRedisTemplate,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.food.ingredient-retro.estimated-krw-per-call:0.3}") private val estimatedKrwPerCall: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val running = AtomicInteger()
    private val coordinator = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(JOB_QUEUE)) { Thread(it, "ingredient-retro").apply { isDaemon = true } }
    private val workers = Executors.newFixedThreadPool(CONCURRENCY) { Thread(it, "ingredient-retro-call").apply { isDaemon = true } }
    private val activeLocks = ConcurrentHashMap<String, String>()
    @Volatile private var stopping = false

    var decider: IngredientDecider? = deciderProvider.getIfAvailable()

    fun startRetro(codeNames: List<String>, dryRun: Boolean, rejudge: Boolean, foodIds: List<Long>?): FoodIngredientRetroJob {
        val codes = codeNames.map { name -> IngredientCode.entries.firstOrNull { it.name == name } ?: throw BusinessException(ErrorCode.FOOD_UNKNOWN_INGREDIENT) }.distinct().sortedBy { it.name }
        if (codes.isEmpty()) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val jobId = UUID.randomUUID().toString()
        val lockKey = LOCK_PREFIX + codes.joinToString(",") { it.name }
        if (redis.opsForValue().setIfAbsent(lockKey, jobId, LOCK_TTL) != true) throw BusinessException(ErrorCode.FOOD_INGREDIENT_RETRO_IN_PROGRESS)
        val job = FoodIngredientRetroJob(jobId = jobId, codes = codes.map { it.name }, dryRun = dryRun, rejudge = rejudge, status = FoodIngredientRetroJob.Status.RUNNING, startedAt = Instant.now().toString())
        save(job)
        running.incrementAndGet()
        activeLocks[jobId] = lockKey
        try {
            coordinator.execute {
                try {
                    run(job, codes, foodIds)
                } finally {
                    runCatching { redis.execute(DELETE_IF_OWNER, listOf(lockKey), jobId) }
                    activeLocks.remove(jobId)
                    running.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            running.decrementAndGet()
            activeLocks.remove(jobId)
            redis.execute(DELETE_IF_OWNER, listOf(lockKey), jobId)
            throw BusinessException(ErrorCode.FOOD_INGREDIENT_RETRO_IN_PROGRESS)
        }
        return job
    }

    fun getRetroJob(jobId: String): FoodIngredientRetroJob =
        redis.opsForValue().get(JOB_PREFIX + jobId)?.let { mapper.readValue<FoodIngredientRetroJob>(it) }
            ?: throw BusinessException(ErrorCode.FOOD_INGREDIENT_RETRO_JOB_NOT_FOUND)

    @PreDestroy
    fun stopRunningJobs() {
        stopping = true
        val active = activeLocks.toMap()
        coordinator.shutdownNow()
        workers.shutdownNow()
        coordinator.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        active.forEach { (jobId, lockKey) ->
            runCatching {
                redis.opsForValue().get(JOB_PREFIX + jobId)?.let { mapper.readValue<FoodIngredientRetroJob>(it) }
                    ?.takeIf { it.status == FoodIngredientRetroJob.Status.RUNNING }
                    ?.let { write(it.copy(status = FoodIngredientRetroJob.Status.FAILED, finishedAt = Instant.now().toString())) }
                redis.execute(DELETE_IF_OWNER, listOf(lockKey), jobId)
            }.onFailure { log.warn("재료 소급 판정 종료 정리 실패 jobId={}", jobId, it) }
        }
        log.info("재료 소급 판정 종료 — 진행 중이던 작업 {}건을 FAILED 로 닫고 잠금을 풀었다", active.size)
    }

    fun awaitIdle() {
        val deadline = System.nanoTime() + IDLE_WAIT_NANOS
        while (running.get() > 0 && System.nanoTime() < deadline) Thread.sleep(IDLE_POLL_MILLIS)
    }

    private fun run(initial: FoodIngredientRetroJob, codes: List<IngredientCode>, foodIds: List<Long>?) {
        var job = initial
        val lock = Any()
        fun update(change: (FoodIngredientRetroJob) -> FoodIngredientRetroJob) = synchronized(lock) {
            job = change(job)
            save(job)
        }
        try {
            val ids = foods.findIdsByContentStatus(FoodContentStatus.READY).let { ready -> foodIds?.toSet()?.let { wanted -> ready.filter { it in wanted } } ?: ready }
            update { it.copy(readyCount = ids.size) }
            val tasks = ids.map { id ->
                Callable {
                    try {
                        process(id, codes, job.dryRun, job.rejudge, ::update)
                    } catch (e: Exception) {
                        log.warn("재료 소급 판정 음식 처리 실패 — 건너뛰고 재실행으로 보강 foodId={}", id, e)
                        update { it.copy(failedFoodIds = it.failedFoodIds + id) }
                    }
                }
            }
            workers.invokeAll(tasks).forEach { it.get() }
            update { it.copy(status = FoodIngredientRetroJob.Status.DONE, finishedAt = Instant.now().toString()) }
            log.info("재료 소급 판정 끝 jobId={} codes={} dryRun={} 요약={}", job.jobId, job.codes, job.dryRun, mapper.writeValueAsString(job))
        } catch (e: Exception) {
            log.warn("재료 소급 판정 중단 jobId={}", job.jobId, e)
            update { it.copy(status = FoodIngredientRetroJob.Status.FAILED, finishedAt = Instant.now().toString()) }
        }
    }

    private fun process(foodId: Long, codes: List<IngredientCode>, dryRun: Boolean, rejudge: Boolean, update: ((FoodIngredientRetroJob) -> FoodIngredientRetroJob) -> Unit) {
        val loaded = readTransaction.execute { foods.findById(foodId).orElse(null) }?.takeIf { it.isReady() } ?: return
        if (!isAssessed(loaded)) return update { it.copy(skippedUnassessedFoodIds = it.skippedUnassessedFoodIds + foodId) }
        val food = snapshotOf(loaded)
        val asked = if (rejudge) codes else codes.filter { code -> code.name !in food.currentCodes && !isJudged(code, foodId, loaded.version) }
        if (asked.isEmpty()) return
        update { it.copy(targetCount = it.targetCount + 1, estimatedCostKrw = it.estimatedCostKrw + estimatedKrwPerCall) }
        if (dryRun) return
        val decider = decider ?: return update { it.copy(failedFoodIds = it.failedFoodIds + foodId) }
        val decisions = try {
            decider.decide(food, asked)
        } catch (e: Exception) {
            log.warn("재료 소급 판정 실패 — 건너뛰고 재실행으로 보강 foodId={}", foodId, e)
            return update { it.copy(calledCount = it.calledCount + 1, failedFoodIds = it.failedFoodIds + foodId) }
        }
        val percents = asked.filter { it in decisions.byCode }.associateWith { code ->
            decisions.byCode.getValue(code).probability.takeIf { it >= MIN_PROBABILITY }?.let { (it * 100).roundToInt().coerceIn(1, 100) }
        }
        val result = try {
            if (!rejudge && percents.values.all { it == null }) {
                Result(Outcome.NONE, loaded.version)
            } else {
                writeTransaction.execute { apply(foodId, percents, rejudge) } ?: Result(Outcome.FAILED)
            }
        } catch (e: Exception) {
            log.warn("재료 소급 판정 반영 실패 — 판정 기록을 남기지 않아 재실행 때 다시 부른다 foodId={}", foodId, e)
            Result(Outcome.FAILED)
        }
        if (result.outcome == Outcome.CHANGED || result.outcome == Outcome.NONE) percents.keys.forEach { markJudged(it, foodId, result.version!!) }
        update { current ->
            current.copy(
                calledCount = current.calledCount + 1,
                costKrw = current.costKrw + decisions.costKrw,
                addedFoodCount = current.addedFoodCount + if (result.added > 0) 1 else 0,
                addedCodeCount = current.addedCodeCount + result.added,
                removedCodeCount = current.removedCodeCount + result.removed,
                skippedCapFoodIds = if (result.outcome == Outcome.CAP) current.skippedCapFoodIds + foodId else current.skippedCapFoodIds,
                skippedUnassessedFoodIds = if (result.outcome == Outcome.UNASSESSED) current.skippedUnassessedFoodIds + foodId else current.skippedUnassessedFoodIds,
                failedFoodIds = if (result.outcome == Outcome.FAILED) current.failedFoodIds + foodId else current.failedFoodIds,
                probabilityBuckets = decisions.byCode.entries.fold(current.probabilityBuckets) { buckets, (code, presence) ->
                    val byBucket = buckets[code.name].orEmpty()
                    val bucket = bucketOf(presence.probability)
                    buckets + (code.name to (byBucket + (bucket to (byBucket[bucket] ?: 0) + 1)))
                },
            )
        }
    }

    private enum class Outcome { CHANGED, NONE, CAP, UNASSESSED, NOT_READY, FAILED }

    private data class Result(val outcome: Outcome, val version: Long? = null, val added: Int = 0, val removed: Int = 0)

    private fun apply(foodId: Long, percents: Map<IngredientCode, Int?>, rejudge: Boolean): Result {
        val food = foods.findByIdForUpdate(foodId)?.takeIf { it.isReady() } ?: return Result(Outcome.NOT_READY)
        if (!isAssessed(food)) return Result(Outcome.UNASSESSED)
        val current = food.ingredients.orEmpty()
        val decided = percents.mapKeys { it.key.name }.filter { (code, percent) -> rejudge || (percent != null && current.none { it.code == code }) }
        val next = current.mapNotNull { existing -> if (existing.code in decided) decided[existing.code]?.let { FoodIngredient(existing.code, it) } else existing } +
            decided.filter { (code, percent) -> percent != null && current.none { it.code == code } }.map { (code, percent) -> FoodIngredient(code, percent!!) }
        if (next == current) return Result(Outcome.NONE, food.version)
        if (next.size > Food.MAX_INGREDIENTS) return Result(Outcome.CAP)
        food.replaceIngredients(next)
        foodIngredients.replace(food.id, food.ingredients)
        foods.flush()
        val before = current.map { it.code }.toSet()
        val after = next.map { it.code }.toSet()
        return Result(Outcome.CHANGED, food.version, added = (after - before).size, removed = (before - after).size)
    }

    private fun isAssessed(food: Food): Boolean = food.ingredientsAssessed && food.ingredients != null

    private fun snapshotOf(food: Food) = IngredientDecisionFood(
        koreanName = food.koreanName,
        description = food.description,
        longDescription = food.longDescription,
        currentCodes = food.ingredients.orEmpty().map { it.code },
    )

    private fun isJudged(code: IngredientCode, foodId: Long, version: Long): Boolean =
        redis.opsForHash<String, String>().get(JUDGED_PREFIX + code.name, foodId.toString()) == version.toString()

    private fun markJudged(code: IngredientCode, foodId: Long, version: Long) {
        runCatching { redis.opsForHash<String, String>().put(JUDGED_PREFIX + code.name, foodId.toString(), version.toString()) }
            .onFailure { log.warn("재료 소급 판정 기록 실패 — 다음 실행에서 다시 부른다 foodId={} code={}", foodId, code, it) }
    }

    private fun save(job: FoodIngredientRetroJob) {
        if (!stopping) write(job)
    }

    private fun write(job: FoodIngredientRetroJob) {
        redis.opsForValue().set(JOB_PREFIX + job.jobId, mapper.writeValueAsString(job), JOB_TTL)
    }

    companion object {
        const val CONCURRENCY = 5
        const val MIN_PROBABILITY = 0.01
        private const val JOB_QUEUE = 10
        private const val LOCK_PREFIX = "ingredient-retro:lock:"
        private const val JOB_PREFIX = "ingredient-retro:job:"
        private const val JUDGED_PREFIX = "ingredient-retro:judged-version:"
        private val LOCK_TTL = Duration.ofHours(6)
        private val JOB_TTL = Duration.ofDays(7)
        private const val IDLE_WAIT_NANOS = 30_000_000_000L
        private const val IDLE_POLL_MILLIS = 50L
        private const val SHUTDOWN_WAIT_SECONDS = 5L
        private val DELETE_IF_OWNER = DefaultRedisScript(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long::class.java,
        )

        fun bucketOf(probability: Double): String = when {
            probability < 0.01 -> "lt1"
            probability < 0.10 -> "1to10"
            probability < 0.50 -> "10to50"
            probability < 0.90 -> "50to90"
            else -> "ge90"
        }
    }
}
