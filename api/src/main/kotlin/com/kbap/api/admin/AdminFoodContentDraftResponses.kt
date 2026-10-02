package com.kbap.api.admin

import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentDraft
import com.kbap.common.domain.food.model.FoodIngredient
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Schema(description = "검수 대기 콘텐츠 초안 목록 — 공개(READY) 음식의 재수집 결과")
data class AdminFoodContentDraftPageResponse(
    val items: List<Summary>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
) {
    @Schema(name = "AdminFoodContentDraftSummary", description = "초안 한 건 요약")
    data class Summary(
        val foodId: Long,
        val koreanName: String,
        val draftId: Long,
        @field:Schema(description = "이 초안을 만든 콘텐츠 요청(아웃박스) id")
        val outboxId: Long,
        val createdAt: LocalDateTime,
    )

    companion object {
        fun from(page: AdminFoodContentDraftPage) = AdminFoodContentDraftPageResponse(
            items = page.items.map { (food, draft) -> Summary(food.id, food.koreanName, draft.id, draft.outboxId, draft.createdAt) },
            page = page.page,
            size = page.size,
            totalCount = page.totalCount,
            totalPages = if (page.size == 0) 0 else ((page.totalCount + page.size - 1) / page.size).toInt(),
        )
    }
}

@Schema(description = "콘텐츠 초안 — 지금 공개 중인 값과 나란히")
data class AdminFoodContentDraftResponse(
    val foodId: Long,
    val koreanName: String,
    val draftId: Long,
    val outboxId: Long,
    val createdAt: LocalDateTime,
    @field:Schema(description = "비교한 공개 값의 버전 — 승인 요청에 그대로 돌려보낸다. 그 사이 공개 내용이 바뀌었으면 승인은 409(FOOD-006)")
    val foodVersion: Long,
    @field:Schema(description = "지금 공개 중인 값")
    val current: Content,
    @field:Schema(description = "검수 대기 중인 초안 값 — 승인하면 current 를 이 값으로 바꾼다")
    val draft: Content,
) {
    @Schema(name = "AdminFoodContentDraftContent", description = "음식 콘텐츠 값")
    data class Content(
        val description: String,
        @field:Schema(nullable = true)
        val longDescription: String?,
        @field:Schema(description = "-1 = 미평가, 0~10")
        val spiciness: Int,
        val nameTranslations: Map<String, String>,
        val descriptionTranslations: Map<String, String>,
        @field:Schema(description = "재료 코드·비율 — 위험도 판정의 근거", nullable = true)
        val ingredients: List<FoodIngredient>?,
    )

    companion object {
        fun from(food: Food, draft: FoodContentDraft) = AdminFoodContentDraftResponse(
            foodId = food.id,
            koreanName = food.koreanName,
            draftId = draft.id,
            outboxId = draft.outboxId,
            createdAt = draft.createdAt,
            foodVersion = food.version,
            current = Content(
                food.description,
                food.longDescription,
                food.spiciness,
                food.nameTranslations,
                food.descriptionTranslations,
                food.ingredients,
            ),
            draft = Content(
                draft.description,
                draft.longDescription,
                draft.spiciness,
                draft.nameTranslations,
                draft.descriptionTranslations,
                draft.ingredients,
            ),
        )
    }
}

@Schema(description = "콘텐츠 초안 검수 결과 입력")
data class AdminFoodContentDraftReviewRequest(
    @field:NotNull(message = "draftId 는 필수입니다")
    @field:Schema(description = "비교 화면에서 본 초안 id — 그 사이 새 결과로 대체됐으면 404(FOOD-021)", example = "12")
    val draftId: Long?,

    @field:NotNull(message = "foodVersion 은 필수입니다")
    @field:Schema(description = "비교 화면에서 받은 foodVersion — 승인 시 공개 값이 그 사이 바뀌었으면 409(FOOD-006)", example = "3")
    val foodVersion: Long?,

    @field:NotNull(message = "passed 는 필수입니다")
    @field:Schema(description = "true = 승인(공개 내용을 초안으로 교체), false = 반려(공개 내용 무변)", example = "true")
    val passed: Boolean?,

    @field:Size(max = FoodContentDraft.MAX_REJECT_REASON_LENGTH, message = "reason 은 최대 500자입니다")
    @field:Schema(description = "반려 사유", nullable = true)
    val reason: String? = null,
)

@Schema(description = "콘텐츠 초안 검수 결과")
data class AdminFoodContentDraftReviewResponse(
    val foodId: Long,
    val draftId: Long,
    @field:Schema(example = "APPROVED")
    val reviewStatus: String,
) {
    companion object {
        fun from(draft: FoodContentDraft) = AdminFoodContentDraftReviewResponse(draft.foodId, draft.id, draft.reviewStatus.name)
    }
}
