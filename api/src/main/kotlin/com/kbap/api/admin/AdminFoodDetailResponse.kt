package com.kbap.api.admin

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Duration
import java.time.LocalDateTime

data class AdminFoodDetailResponse(
    val id: Long,
    val koreanName: String,
    val displayName: String,
    val matchKey: String,
    val deleted: Boolean,
    val description: String,
    val longDescription: String?,
    val spiciness: Int,
    val contentStatus: FoodContentStatus,
    val contentFailureKind: FoodContentFailureKind?,
    val contentReviewRejectionReason: String?,
    val contentReviewAttempts: Int,
    val imageRef: String?,
    val imageUrl: String?,
    val nameTranslations: Map<String, String>,
    val descriptionTranslations: Map<String, String>,
    val ingredients: List<FoodIngredient>?,
    val version: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    @field:Schema(description = "사람 검수 기록. 검수 전·해제 후는 null", nullable = true)
    val humanReview: HumanReviewResponse?,
    @field:Schema(description = "마지막 이미지 재생성 상태(갤러리 응답과 같은 값). 이력 없음·마지막 성공이면 null", nullable = true)
    val regeneration: AdminRegenerationStateResponse?,
    @field:Schema(description = "추가 이미지 생성이 진행 중인지(갤러리 응답과 같은 값)", example = "false")
    val additionalInProgress: Boolean,
    @field:Schema(
        description = "이 음식에 처리 중인 콘텐츠 수집 요청이 있는지 — 발행 대기(PENDING)이거나 보냄(SENT)·미완료·포기(dead) 아님이면 true. " +
            "재수집 뒤 결과(반영·초안)를 기다리는 화면은 false 가 되면 대기를 끝낸다. 재수집 건너뜀·회수 잡과 같은 '진행 중 요청' 정의를 쓴다. " +
            "삭제된 음식은 항상 false 다 — 그 요청의 결과는 반영되지 않는다. " +
            "굳은 SENT(24시간 회수 전)도 true 다 — 회수 잡이 다시 보내거나 포기로 넘길 때까지 진행 중으로 본다",
        example = "false",
    )
    val contentRequestPending: Boolean,
    @field:Schema(
        description = "contentRequestPending 과 같은 판정으로 잡힌 진행 중 요청 중 가장 최근 요청의 생성 시각(UTC 순간). 없으면 null. " +
            "보낸 시각이 아니라 요청이 만들어진 시각이다 — 대기 화면은 이 값으로 경과 시간을 보여 주고 클라이언트가 시작 시각을 따로 들고 있지 않는다",
        nullable = true,
        example = "2026-10-01T06:15:00Z",
    )
    val contentRequestSince: java.time.Instant?,
    @field:Schema(
        description = "contentRequestSince 부터 지난 시간을 서버가 응답 시점에 계산한 경과 초. 진행 중 요청이 없으면 null(contentRequestSince 와 함께). " +
            "대기 상한 판정은 이 값으로 한다 — 클라이언트 시계로 since 와의 차이를 계산하면 PC 시계가 틀릴 때 어긋난다. 0 이상이다",
        nullable = true,
        example = "420",
    )
    val contentRequestAgeSeconds: Long?,
    val pendingContentDraftId: Long?,
) {
    companion object {
        fun from(
            food: Food,
            imagePublicBaseUrl: String,
            humanReview: HumanReviewResponse?,
            regeneration: AdminRegenerationStateResponse?,
            additionalInProgress: Boolean,
            contentRequestPending: Boolean,
            contentRequestSince: java.time.Instant?,
            now: java.time.Instant,
            pendingContentDraftId: Long?,
        ): AdminFoodDetailResponse =
            AdminFoodDetailResponse(
                id = food.id,
                koreanName = food.displayName(LanguageCode.KO),
                displayName = food.displayName,
                matchKey = food.deletedOriginalKoreanName ?: food.koreanName,
                deleted = food.isDeleted(),
                description = food.description,
                longDescription = food.longDescription,
                spiciness = food.spiciness,
                contentStatus = food.contentStatus,
                contentFailureKind = food.contentFailureKind,
                contentReviewRejectionReason = food.contentReviewRejectionReason,
                contentReviewAttempts = food.contentReviewAttempts,
                imageRef = food.imageRef,
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
                nameTranslations = food.nameTranslations,
                descriptionTranslations = food.descriptionTranslations,
                ingredients = food.ingredients,
                version = food.version,
                createdAt = food.createdAt,
                updatedAt = food.updatedAt,
                humanReview = humanReview,
                regeneration = regeneration,
                additionalInProgress = additionalInProgress,
                contentRequestPending = contentRequestPending,
                contentRequestSince = contentRequestSince,
                contentRequestAgeSeconds = contentRequestSince?.let { Duration.between(it, now).seconds.coerceAtLeast(0) },
                pendingContentDraftId = pendingContentDraftId,
            )
    }
}

data class AdminFoodUpdateRequest(
    @field:NotBlank
    @field:Size(max = 255, message = "koreanName 은 255자 이하여야 합니다")
    val koreanName: String? = null,
    @field:Size(max = 255, message = "displayName 은 255자 이하여야 합니다")
    val displayName: String? = null,
    @field:NotNull
    @field:Size(max = 255, message = "description 은 255자 이하여야 합니다")
    val description: String? = null,
    @field:NotNull
    @field:Min(-1, message = "spiciness 는 -1(미조사) 이상이어야 합니다")
    @field:Max(10, message = "spiciness 는 10 이하여야 합니다")
    val spiciness: Int? = null,
    @field:NotNull
    val contentStatus: FoodContentStatus? = null,
    @field:Size(max = 500, message = "imageRef 는 500자 이하여야 합니다")
    @field:Schema(
        description = "대표 이미지 키. **생략하면 변경 없음**이다. 값을 주려면 현재 키와 같아야 하며, " +
            "다른 키를 주면 400(FOOD-012)이다 — 대표 이미지는 갤러리 대표 지정 API 로만 바꾼다.",
        nullable = true,
    )
    val imageRef: String? = null,
    val nameTranslations: Map<String, String>? = null,
    val descriptionTranslations: Map<String, String>? = null,
    val ingredients: List<FoodIngredient>? = null,
    @field:NotNull
    val version: Long? = null,
) {
    @AssertTrue(message = "ingredients 의 code 는 성분 카탈로그 코드여야 합니다")
    fun isIngredientCodesKnown(): Boolean = ingredients.orEmpty().all { it.code in KNOWN_INGREDIENT_CODES }

    @AssertTrue(message = "displayName 은 공백일 수 없습니다")
    fun isDisplayNamePresentable(): Boolean = displayName == null || displayName.isNotBlank()

    companion object {
        private val KNOWN_INGREDIENT_CODES: Set<String> = IngredientCode.entries.map { it.name }.toSet()
    }
}

data class AdminFoodRestoreResponse(
    val restored: Boolean,
    val contentStatus: FoodContentStatus,
)

data class AdminFoodRecollectResponse(
    val requested: Long,
    val created: Long,
    val skipped: Long,
    @field:Schema(description = "skipped 중 이미지 재생성이 진행 중이라 건너뛴 수(FOOD-020 사유). 재생성이 끝난 뒤 다시 요청한다", example = "0")
    val skippedRegenerating: Long,
    val exceeded: Boolean,
    val max: Int,
    @field:Schema(
        description = "단건 재수집에서만 의미 — 이 음식에 검수 대기 콘텐츠 초안이 이미 있으면 true. " +
            "재수집은 그대로 접수되고, 새 결과가 오면 그 초안을 대체한다(공개 음식의 결과는 검수 뒤에만 반영)",
        example = "false",
    )
    val pendingDraft: Boolean,
) {
    companion object {
        fun from(result: AdminFoodRecollectResult): AdminFoodRecollectResponse =
            AdminFoodRecollectResponse(
                requested = result.requested,
                created = result.created,
                skipped = result.skipped,
                skippedRegenerating = result.skippedRegenerating,
                exceeded = result.exceeded,
                max = result.max,
                pendingDraft = result.pendingDraft,
            )
    }
}
