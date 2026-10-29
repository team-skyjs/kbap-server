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

    var decider: IngredientDecider? = deciderProvider.getIfAvailable()

    fun startRetro(codeNames: List<String>, dryRun: Boolean, foodIds: List<Long>?): FoodIngredientRetroJob {
        val codes = codeNames.map { name -> IngredientCode.entries.firstOrNull { it.name == name } ?: throw BusinessException(ErrorCode.FOOD_UNKNOWN_INGREDIENT) }.distinct().sortedBy { it.name }
        if (codes.isEmpty()) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val jobId = UUID.randomUUID().toString()
        val lockKey = LOCK_PREFIX + codes.joinToString(",") { it.name }
        if (redis.opsForValue().setIfAbsent(lockKey, jobId, LOCK_TTL) != true) throw BusinessException(ErrorCode.FOOD_INGREDIENT_RETRO_IN_PROGRESS)
        val job = FoodIngredientRetroJob(jobId = jobId, codes = codes.map { it.name }, dryRun = dryRun, status = FoodIngredientRetroJob.Status.RUNNING, startedAt = Instant.now().toString())
        save(job)
        running.incrementAndGet()
        try {
            coordinator.execute {
                try {
                    run(job, codes, foodIds)
                } finally {
                    runCatching { redis.execute(DELETE_IF_OWNER, listOf(lockKey), jobId) }
                    running.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            running.decrementAndGet()
            redis.execute(DELETE_IF_OWNER, listOf(lockKey), jobId)
            throw BusinessException(ErrorCode.FOOD_INGREDIENT_RETRO_IN_PROGRESS)
        }
        return job
    }

    fun getRetroJob(jobId: String): FoodIngredientRetroJob =
        redis.opsForValue().get(JOB_PREFIX + jobId)?.let { mapper.readValue<FoodIngredientRetroJob>(it) }
            ?: throw BusinessException(ErrorCode.FOOD_INGREDIENT_RETRO_JOB_NOT_FOUND)

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
                        process(id, codes, job.dryRun, ::update)
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

    private fun process(foodId: Long, codes: List<IngredientCode>, dryRun: Boolean, update: ((FoodIngredientRetroJob) -> FoodIngredientRetroJob) -> Unit) {
        val loaded = readTransaction.execute { foods.findById(foodId).orElse(null) }?.takeIf { it.isReady() } ?: return
        if (!isAssessed(loaded)) return update { it.copy(skippedUnassessedFoodIds = it.skippedUnassessedFoodIds + foodId) }
        val food = snapshotOf(loaded)
        val missing = codes.filter { code -> code.name !in food.currentCodes && !isJudged(code, foodId) }
        if (missing.isEmpty()) return
        update { it.copy(targetCount = it.targetCount + 1, estimatedCostKrw = it.estimatedCostKrw + estimatedKrwPerCall) }
        if (dryRun) return
        val decider = decider ?: return update { it.copy(failedFoodIds = it.failedFoodIds + foodId) }
        val decisions = try {
            decider.decide(food, missing)
        } catch (e: Exception) {
            log.warn("재료 소급 판정 실패 — 건너뛰고 재실행으로 보강 foodId={}", foodId, e)
            return update { it.copy(calledCount = it.calledCount + 1, failedFoodIds = it.failedFoodIds + foodId) }
        }
        val additions = missing.mapNotNull { code ->
            decisions.byCode[code]?.probability?.takeIf { it >= MIN_PROBABILITY }?.let { FoodIngredient(code.name, (it * 100).roundToInt().coerceIn(1, 100)) }
        }
        val outcome = try {
            if (additions.isEmpty()) Outcome.NONE else writeTransaction.execute { apply(foodId, additions) } ?: Outcome.NONE
        } catch (e: Exception) {
            log.warn("재료 소급 판정 반영 실패 — 판정 기록을 남기지 않아 재실행 때 다시 부른다 foodId={}", foodId, e)
            Outcome.FAILED
        }
        if (outcome != Outcome.FAILED) missing.filter { it in decisions.byCode }.forEach { markJudged(it, foodId) }
        update { current ->
            current.copy(
                calledCount = current.calledCount + 1,
                costKrw = current.costKrw + decisions.costKrw,
                addedFoodCount = current.addedFoodCount + if (outcome == Outcome.ADDED) 1 else 0,
                addedCodeCount = current.addedCodeCount + if (outcome == Outcome.ADDED) additions.size else 0,
                skippedCapFoodIds = if (outcome == Outcome.CAP) current.skippedCapFoodIds + foodId else current.skippedCapFoodIds,
                skippedUnassessedFoodIds = if (outcome == Outcome.UNASSESSED) current.skippedUnassessedFoodIds + foodId else current.skippedUnassessedFoodIds,
                failedFoodIds = if (outcome == Outcome.FAILED) current.failedFoodIds + foodId else current.failedFoodIds,
                probabilityBuckets = decisions.byCode.entries.fold(current.probabilityBuckets) { buckets, (code, presence) ->
                    val byBucket = buckets[code.name].orEmpty()
                    val bucket = bucketOf(presence.probability)
                    buckets + (code.name to (byBucket + (bucket to (byBucket[bucket] ?: 0) + 1)))
                },
            )
        }
    }

    private enum class Outcome { ADDED, CAP, UNASSESSED, FAILED, NONE }

    private fun apply(foodId: Long, additions: List<FoodIngredient>): Outcome {
        val food = foods.findByIdForUpdate(foodId)?.takeIf { it.isReady() } ?: return Outcome.NONE
        if (!isAssessed(food)) return Outcome.UNASSESSED
        val current = food.ingredients.orEmpty()
        val fresh = additions.filter { addition -> current.none { it.code == addition.code } }
        if (fresh.isEmpty()) return Outcome.NONE
        if (current.size + fresh.size > Food.MAX_INGREDIENTS) return Outcome.CAP
        food.addIngredients(fresh)
        foodIngredients.replace(food.id, food.ingredients)
        return Outcome.ADDED
    }

    private fun isAssessed(food: Food): Boolean = food.ingredientsAssessed && food.ingredients != null

    private fun snapshotOf(food: Food) = IngredientDecisionFood(
        koreanName = food.koreanName,
        description = food.description,
        longDescription = food.longDescription,
        currentCodes = food.ingredients.orEmpty().map { it.code },
    )

    private fun isJudged(code: IngredientCode, foodId: Long): Boolean = redis.opsForSet().isMember(JUDGED_PREFIX + code.name, foodId.toString()) == true

    private fun markJudged(code: IngredientCode, foodId: Long) {
        runCatching { redis.opsForSet().add(JUDGED_PREFIX + code.name, foodId.toString()) }
            .onFailure { log.warn("재료 소급 판정 기록 실패 — 다음 실행에서 다시 부른다 foodId={} code={}", foodId, code, it) }
    }

    private fun save(job: FoodIngredientRetroJob) {
        redis.opsForValue().set(JOB_PREFIX + job.jobId, mapper.writeValueAsString(job), JOB_TTL)
    }

    companion object {
        const val CONCURRENCY = 5
        const val MIN_PROBABILITY = 0.01
        private const val JOB_QUEUE = 10
        private const val LOCK_PREFIX = "ingredient-retro:lock:"
        private const val JOB_PREFIX = "ingredient-retro:job:"
        private const val JUDGED_PREFIX = "ingredient-retro:judged:"
        private val LOCK_TTL = Duration.ofHours(6)
        private val JOB_TTL = Duration.ofDays(7)
        private const val IDLE_WAIT_NANOS = 30_000_000_000L
        private const val IDLE_POLL_MILLIS = 50L
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
