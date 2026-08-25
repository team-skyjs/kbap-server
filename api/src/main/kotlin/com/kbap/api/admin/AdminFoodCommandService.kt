package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodTransition
import com.kbap.common.domain.food.model.FoodTransitionException
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.util.KoreanMenuNameNormalizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminFoodCommandService(
    private val foodRepository: FoodJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
    private val auditRecorder: AdminAuditRecorder,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional
    fun updateFood(adminId: Long, id: Long, request: AdminFoodUpdateRequest): AdminFoodDetailResponse {
        val food = getFood(id)
        if (request.version != food.version) {
            throw BusinessException(ErrorCode.CONFLICT, payload = mapOf("currentVersion" to food.version))
        }
        val ingredients = request.ingredients?.map { it.toIngredient() }
        val errors = FoodContentValidator.validate(
            FoodContentCandidate(
                koreanName = request.koreanName,
                description = request.description,
                longDescription = request.longDescription,
                spiciness = request.spiciness,
                nameTranslations = request.nameTranslations,
                descriptionTranslations = request.descriptionTranslations,
                ingredients = ingredients,
            ),
            requireComplete = requiresCompleteContent(food),
        )
        if (errors.isNotEmpty()) throw BusinessException(ErrorCode.FOOD_INVALID_CONTENT, payload = mapOf("errors" to errors))

        val displayName = request.koreanName!!.trim()
        val matchKey = KoreanMenuNameNormalizer.matchKey(displayName)
        if (foodRepository.findByKoreanNameIn(setOf(matchKey)).any { it.id != food.id }) {
            throw BusinessException(ErrorCode.DUPLICATE_FOOD_NAME)
        }

        val before = snapshot(food)
        food.koreanName = matchKey
        food.displayName = displayName
        food.description = request.description!!
        food.longDescription = request.longDescription
        food.spiciness = request.spiciness!!
        food.imageRef = request.imageRef?.trim()?.takeIf { it.isNotEmpty() }
        food.nameTranslations = request.nameTranslations!!
        food.descriptionTranslations = request.descriptionTranslations!!
        food.ingredients = ingredients
        foodRepository.flush()
        if (food.isReady()) vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.UPSERT)
        auditRecorder.record(adminId, AdminAuditAction.FOOD_UPDATE, AdminAuditTargetType.FOOD, food.id, before, snapshot(food))
        return AdminFoodDetailResponse.from(food, imagePublicBaseUrl)
    }

    @Transactional
    fun approve(adminId: Long, id: Long): AdminFoodTransitionResponse {
        val food = getFood(id)
        if (food.isReady()) return AdminFoodTransitionResponse.from(food)
        return applyTransition(adminId, food, FoodTransition.APPROVE, null, AdminAuditAction.FOOD_APPROVE)
    }

    @Transactional
    fun reject(adminId: Long, id: Long, reason: String): AdminFoodTransitionResponse =
        applyTransition(adminId, getFood(id), FoodTransition.REJECT, reason, AdminAuditAction.FOOD_REJECT)

    @Transactional
    fun transition(adminId: Long, id: Long, transition: FoodTransition, reason: String?): AdminFoodTransitionResponse {
        val action = when (transition) {
            FoodTransition.APPROVE -> AdminAuditAction.FOOD_APPROVE
            FoodTransition.REJECT -> AdminAuditAction.FOOD_REJECT
            else -> AdminAuditAction.FOOD_TRANSITION
        }
        return applyTransition(adminId, getFood(id), transition, reason, action)
    }

    @Transactional
    fun deleteFood(adminId: Long, id: Long): AdminFoodTransitionResponse {
        val food = foodRepository.findByIdIncludingDeleted(id) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        if (food.isDeleted()) return AdminFoodTransitionResponse.from(food)
        food.delete()
        vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.DELETE)
        auditRecorder.record(adminId, AdminAuditAction.FOOD_DELETE, AdminAuditTargetType.FOOD, food.id, mapOf("deleted" to false), mapOf("deleted" to true))
        return AdminFoodTransitionResponse.from(food)
    }

    @Transactional
    fun restoreFood(adminId: Long, id: Long): AdminFoodDetailResponse {
        val deleted = foodRepository.findByIdIncludingDeleted(id) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        if (deleted.isDeleted()) {
            foodRepository.restore(id)
            auditRecorder.record(adminId, AdminAuditAction.FOOD_RESTORE, AdminAuditTargetType.FOOD, id, mapOf("deleted" to true), mapOf("deleted" to false))
        }
        val food = getFood(id)
        if (food.isReady()) vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.UPSERT)
        return AdminFoodDetailResponse.from(food, imagePublicBaseUrl)
    }

    private fun applyTransition(
        adminId: Long,
        food: Food,
        transition: FoodTransition,
        reason: String?,
        action: AdminAuditAction,
    ): AdminFoodTransitionResponse {
        val before = food.contentStatus
        try {
            food.transition(transition, reason)
        } catch (e: FoodTransitionException) {
            throw BusinessException(
                ErrorCode.FOOD_INVALID_TRANSITION,
                payload = mapOf("reason" to e.reason, "allowed" to e.allowed.map { it.name }),
            )
        }
        when (transition) {
            FoodTransition.APPROVE -> vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.UPSERT)
            FoodTransition.UNPUBLISH -> vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.DELETE)
            else -> Unit
        }
        auditRecorder.record(
            adminId,
            action,
            AdminAuditTargetType.FOOD,
            food.id,
            mapOf("contentStatus" to before.name),
            mapOf("contentStatus" to food.contentStatus.name),
            note = reason ?: transition.name,
        )
        return AdminFoodTransitionResponse.from(food)
    }

    private fun getFood(id: Long): Food =
        foodRepository.findById(id).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }

    companion object {
        fun requiresCompleteContent(food: Food): Boolean =
            food.contentStatus == FoodContentStatus.READY || food.contentStatus == FoodContentStatus.PENDING_REVIEW

        fun snapshot(food: Food): Map<String, Any?> = mapOf(
            "koreanName" to food.koreanName,
            "displayName" to food.displayName,
            "description" to food.description,
            "longDescription" to food.longDescription,
            "spiciness" to food.spiciness,
            "imageRef" to food.imageRef,
            "nameTranslations" to food.nameTranslations,
            "descriptionTranslations" to food.descriptionTranslations,
            "ingredients" to food.ingredients?.map { mapOf("code" to it.code, "inclusionPercent" to it.inclusionPercent) },
        )
    }
}
