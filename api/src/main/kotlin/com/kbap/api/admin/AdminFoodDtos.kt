package com.kbap.api.admin

import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodTransition
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Null
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

@Schema(description = "재료 항목")
data class AdminIngredientInput(
    @field:NotBlank val code: String?,
    @field:NotNull val inclusionPercent: Int?,
) {
    fun toIngredient(): FoodIngredient = FoodIngredient(code = code!!, inclusionPercent = inclusionPercent!!)
}

@Schema(description = "음식 수정 요청 — 상태(contentStatus)는 받지 않는다(전이 API 전용). 보내면 400")
data class AdminFoodUpdateRequest(
    @field:Null(message = "contentStatus 는 수정 API 로 바꿀 수 없습니다 — 승인/반려/전이 API 를 사용하세요")
    @field:Schema(hidden = true)
    val contentStatus: String? = null,
    @field:NotNull(message = "version 은 필수입니다")
    @field:Schema(description = "상세 조회 시 받은 버전(낙관락)", example = "4")
    val version: Long?,
    @field:NotBlank(message = "koreanName 은 필수입니다")
    val koreanName: String?,
    val description: String?,
    val longDescription: String? = null,
    val spiciness: Int?,
    val imageRef: String? = null,
    val nameTranslations: Map<String, String>?,
    val descriptionTranslations: Map<String, String>?,
    val ingredients: List<AdminIngredientInput>?,
)

data class AdminFoodRejectRequest(
    @field:NotBlank(message = "reason 은 필수입니다")
    val reason: String?,
)

data class AdminFoodTransitionRequest(
    @field:NotNull(message = "transition 은 필수입니다")
    val transition: FoodTransition?,
    val reason: String? = null,
)

data class AdminFoodStatusResponse(
    val code: FoodContentStatus,
    val label: String,
) {
    companion object {
        fun from(status: FoodContentStatus) = AdminFoodStatusResponse(status, status.displayName)
    }
}

data class AdminIngredientResponse(
    val code: String,
    val inclusionPercent: Int,
) {
    companion object {
        fun from(ingredient: FoodIngredient) = AdminIngredientResponse(ingredient.code, ingredient.inclusionPercent)
    }
}

data class AdminFoodTransitionResponse(
    val id: Long,
    val contentStatus: AdminFoodStatusResponse,
    val allowedTransitions: List<FoodTransition>,
    val version: Long,
) {
    companion object {
        fun from(food: Food) = AdminFoodTransitionResponse(
            id = food.id,
            contentStatus = AdminFoodStatusResponse.from(food.contentStatus),
            allowedTransitions = food.allowedTransitions().toList(),
            version = food.version,
        )
    }
}

data class AdminFoodDetailResponse(
    val id: Long,
    val koreanName: String,
    val displayName: String,
    val description: String,
    val longDescription: String?,
    val spiciness: Int,
    val contentStatus: AdminFoodStatusResponse,
    val contentFailureKind: String?,
    val contentReviewRejectionReason: String?,
    val contentReviewAttempts: Int,
    val imageRef: String?,
    val imageUrl: String?,
    val nameTranslations: Map<String, String>,
    val descriptionTranslations: Map<String, String>,
    val ingredients: List<AdminIngredientResponse>?,
    val allowedTransitions: List<FoodTransition>,
    val version: Long,
    val deleted: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val history: AdminFoodHistoryResponse? = null,
) {
    companion object {
        fun from(food: Food, imagePublicBaseUrl: String, history: AdminFoodHistoryResponse? = null) =
            AdminFoodDetailResponse(
                id = food.id,
                koreanName = food.koreanName,
                displayName = food.displayName,
                description = food.description,
                longDescription = food.longDescription,
                spiciness = food.spiciness,
                contentStatus = AdminFoodStatusResponse.from(food.contentStatus),
                contentFailureKind = food.contentFailureKind?.name,
                contentReviewRejectionReason = food.contentReviewRejectionReason,
                contentReviewAttempts = food.contentReviewAttempts,
                imageRef = food.imageRef,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
                nameTranslations = food.nameTranslations,
                descriptionTranslations = food.descriptionTranslations,
                ingredients = food.ingredients?.map { AdminIngredientResponse.from(it) },
                allowedTransitions = food.allowedTransitions().toList(),
                version = food.version,
                deleted = food.isDeleted(),
                createdAt = food.createdAt,
                updatedAt = food.updatedAt,
                history = history,
            )
    }
}

data class AdminFoodHistoryResponse(
    val contentOutboxes: List<Map<String, Any?>> = emptyList(),
    val imageItems: List<Map<String, Any?>> = emptyList(),
    val vectorOutboxes: List<Map<String, Any?>> = emptyList(),
    val reviewSummary: Map<String, Any?> = emptyMap(),
    val scanMatchCount: Long = 0,
    val bookmarkCount: Long = 0,
    val auditLogs: List<AdminAuditLogResponse> = emptyList(),
)

data class AdminFoodListItemResponse(
    val id: Long,
    val displayName: String,
    val koreanName: String,
    val contentStatus: AdminFoodStatusResponse,
    val contentFailureKind: String?,
    val spiciness: Int,
    val hasImage: Boolean,
    val imageUrl: String?,
    val contentReviewAttempts: Int,
    val reviewCount: Long,
    val vectorSyncStatus: String,
    val deleted: Boolean,
    val updatedAt: LocalDateTime,
)

data class AdminFoodListResponse(
    val items: List<AdminFoodListItemResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminIngredientCatalogResponse(
    val items: List<AdminIngredientCatalogItem>,
)

data class AdminIngredientCatalogItem(
    val code: String,
    val koreanName: String,
    val translations: Map<String, String>,
    val imageUrl: String?,
)
