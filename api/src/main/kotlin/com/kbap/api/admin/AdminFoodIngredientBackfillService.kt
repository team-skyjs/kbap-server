package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodIngredientBackfillJdbcRepository
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.FoodIngredientRelation
import com.kbap.common.domain.food.FoodIngredientSource
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicBoolean

@Service
class AdminFoodIngredientBackfillService(
    private val backfillRepository: FoodIngredientBackfillJdbcRepository,
    private val foodIngredientRepository: FoodIngredientJdbcRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    private val mapper = jacksonObjectMapper()

    private val running = AtomicBoolean(false)

    fun backfill(dryRun: Boolean): AdminFoodIngredientBackfillResponse {
        if (!running.compareAndSet(false, true)) throw BusinessException(ErrorCode.INGREDIENT_BACKFILL_IN_PROGRESS)
        try {
            val foodIds = backfillRepository.findAllSources().map { it.foodId }
            val failures = mutableListOf<AdminFoodIngredientBackfillResponse.Failure>()
            var written = 0
            foodIds.forEach { foodId ->
                if (dryRun) {
                    val source = backfillRepository.findSource(foodId) ?: return@forEach
                    runCatching { storableOf(source) }
                        .onSuccess { written++ }
                        .onFailure { failures += AdminFoodIngredientBackfillResponse.Failure(foodId, it.message.orEmpty()) }
                    return@forEach
                }
                runCatching {
                    transactionTemplate.executeWithoutResult {
                        val source = backfillRepository.findSourceForUpdate(foodId) ?: return@executeWithoutResult
                        val items = storableOf(source)
                        foodIngredientRepository.replace(foodId, items)
                        backfillRepository.markAssessed(foodId, source.rawIngredients != null)
                    }
                }.onSuccess { written++ }
                    .onFailure { error ->
                        log.warn("재료 관계 백필 실패 — foodId={}", foodId, error)
                        failures += AdminFoodIngredientBackfillResponse.Failure(foodId, error.message.orEmpty())
                    }
            }
            return AdminFoodIngredientBackfillResponse(
                dryRun = dryRun,
                scanned = foodIds.size,
                wouldWrite = written,
                failed = failures,
            )
        } finally {
            running.set(false)
        }
    }

    @Transactional(readOnly = true)
    fun getBackfillReport(): AdminFoodIngredientBackfillReportResponse {
        val sources = backfillRepository.findAllSources()
        val relations = backfillRepository.findAllRelations()
        val missingInRelation = mutableListOf<Long>()
        val extraInRelation = mutableListOf<Long>()
        val assessedMismatch = mutableListOf<Long>()
        sources.forEach { source ->
            val expected = runCatching { storableOf(source) }.getOrDefault(emptyList())
                .associate { it.code to it.inclusionPercent }
            val actual = relations[source.foodId].orEmpty()
                .associate { it.code to it.inclusionPercent }
            if (expected.any { (code, percent) -> actual[code] != percent }) missingInRelation += source.foodId
            if (actual.any { (code, percent) -> expected[code] != percent }) extraInRelation += source.foodId
            if (source.assessed != (source.rawIngredients != null)) assessedMismatch += source.foodId
        }
        return AdminFoodIngredientBackfillReportResponse(
            scanned = sources.size,
            missingInRelation = missingInRelation,
            extraInRelation = extraInRelation,
            assessedMismatch = assessedMismatch,
        )
    }

    private fun storableOf(source: FoodIngredientSource): List<FoodIngredient> {
        val raw = source.rawIngredients ?: return emptyList()
        val parsed: List<FoodIngredient> = mapper.readValue(raw)
        val stored = parsed.filter { it.inclusionPercent != 0 }
        require(stored.size <= Food.MAX_INGREDIENTS) { "재료가 ${Food.MAX_INGREDIENTS}개를 넘는다: ${stored.size}" }
        require(stored.distinctBy { it.code }.size == stored.size) { "같은 재료 code 가 두 번 있다" }
        require(stored.all { it.inclusionPercent in STORABLE_PERCENT }) { "포함 확률이 1..100 밖이다" }
        require(stored.all { it.code in KNOWN_CODES }) { "카탈로그에 없는 재료 code 가 있다" }
        return stored
    }

    private companion object {
        val log = LoggerFactory.getLogger(AdminFoodIngredientBackfillService::class.java)

        val STORABLE_PERCENT = 1..100

        val KNOWN_CODES = IngredientCode.entries.map { it.name }.toSet()
    }
}

data class AdminFoodIngredientBackfillResponse(
    val dryRun: Boolean,
    val scanned: Int,
    val wouldWrite: Int,
    val failed: List<Failure>,
) {
    data class Failure(
        val foodId: Long,
        val reason: String,
    )
}

data class AdminFoodIngredientBackfillReportResponse(
    val scanned: Int,
    val missingInRelation: List<Long>,
    val extraInRelation: List<Long>,
    val assessedMismatch: List<Long>,
)
