package com.kbap.common.domain.food.model

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.BaseEntity
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.LocalizedText
import com.kbap.common.util.KoreanMenuNameNormalizer
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.slf4j.LoggerFactory

@Entity
@Table(
    name = "food",
    uniqueConstraints = [UniqueConstraint(name = "uq_food_korean_name", columnNames = ["korean_name"])],
)
class Food(
    @Column(name = "korean_name", nullable = false, length = 255)
    var koreanName: String = "",

    @Column(name = "display_name", nullable = false, length = 255)
    var displayName: String = koreanName,

    @Column(name = "image_ref", length = 500)
    var imageRef: String? = null,

    @Column(name = "description", nullable = false, length = 255)
    var description: String = "",

    @Column(name = "long_description", length = MAX_LONG_DESCRIPTION_LENGTH)
    var longDescription: String? = null,

    @Column(name = "spiciness", nullable = false)
    var spiciness: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "name_translations", nullable = false)
    var nameTranslations: Map<String, String> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "description_translations", nullable = false)
    var descriptionTranslations: Map<String, String> = emptyMap(),

    @Enumerated(EnumType.STRING)
    @Column(
        name = "content_status",
        nullable = false,
        columnDefinition = "ENUM('FAILED','PENDING_IMAGE','PENDING_REVIEW','READY')",
    )
    var contentStatus: FoodContentStatus = FoodContentStatus.READY,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ingredients")
    var ingredients: List<FoodIngredient>? = emptyList(),

    @Column(name = "content_review_attempts", nullable = false, columnDefinition = "int not null default 0")
    var contentReviewAttempts: Int = 0,

    @Column(name = "content_review_rejection_reason", length = MAX_REJECTION_REASON_LENGTH)
    var contentReviewRejectionReason: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(
        name = "content_failure_kind",
        columnDefinition = "ENUM('NOT_FOOD','JUDGE_REJECTED','INGREDIENT_GUARD','ADMIN_REJECTED')",
    )
    var contentFailureKind: FoodContentFailureKind? = null,
) : BaseEntity() {
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false, columnDefinition = "bigint not null default 0")
    var version: Long = 0

    fun isReady(): Boolean = contentStatus == FoodContentStatus.READY

    fun approve(): Boolean {
        // 재승인(READY)은 이미 원하는 결과라 멱등 성공, 그 외 비대상은 운영자 실수 신호라 예외 — 의도된 비대칭.
        if (contentStatus == FoodContentStatus.READY) return false
        requireReviewable()
        contentStatus = FoodContentStatus.READY
        return true
    }

    fun reject(reason: String?) {
        requireReviewable()
        contentStatus = FoodContentStatus.FAILED
        contentFailureKind = FoodContentFailureKind.ADMIN_REJECTED
        contentReviewAttempts++
        contentReviewRejectionReason = truncateReason(reason)
    }

    private fun requireReviewable() {
        if (contentStatus != FoodContentStatus.PENDING_REVIEW) {
            log.warn("검수 대상 아님: foodId={}, contentStatus={}", id, contentStatus)
            throw BusinessException(ErrorCode.FOOD_NOT_REVIEWABLE)
        }
    }

    private fun truncateReason(reason: String?): String? =
        reason
            ?.lineSequence()
            ?.take(MAX_REJECTION_REASON_LINES)
            ?.joinToString("\n")
            ?.take(MAX_REJECTION_REASON_LENGTH)

    fun applyContent(
        description: String,
        longDescription: String?,
        spiciness: Int,
        nameTranslations: Map<String, String>,
        descriptionTranslations: Map<String, String>,
        ingredients: List<FoodIngredient>,
    ) {
        this.description = description
        this.longDescription = longDescription
        this.spiciness = spiciness
        this.nameTranslations = nameTranslations
        this.descriptionTranslations = descriptionTranslations
        this.ingredients = ingredients
        contentFailureKind = null
        contentReviewRejectionReason = null
        if (contentStatus == FoodContentStatus.READY) return
        contentStatus = if (imageRef.isNullOrBlank()) FoodContentStatus.PENDING_IMAGE else FoodContentStatus.PENDING_REVIEW
    }

    fun recordContentFailure(kind: FoodContentFailureKind, reason: String?) {
        contentFailureKind = kind
        contentReviewAttempts++
        contentReviewRejectionReason = truncateReason(reason)
        if (contentStatus == FoodContentStatus.READY) return
        contentStatus = FoodContentStatus.FAILED
    }

    fun attachImage(imageRef: String) {
        require(imageRef.isNotBlank()) { "imageRef 는 blank 일 수 없습니다" }
        require(contentStatus == FoodContentStatus.PENDING_IMAGE) {
            "이미지 부착 대상(PENDING_IMAGE)이 아닙니다: $contentStatus"
        }
        this.imageRef = imageRef
        contentStatus = FoodContentStatus.PENDING_REVIEW
    }

    fun displayName(lang: LanguageCode): String = localizedName().resolve(lang)

    fun description(lang: LanguageCode): String = localizedDescription().resolve(lang)

    fun ingredientsByProbability(): List<FoodIngredient> =
        ingredients.orEmpty().sortedByDescending { it.inclusionPercent }

    fun overallRisk(avoidedCodes: Set<String>): RiskLevel {
        if (!isReady() || ingredients == null) return RiskLevel.UNKNOWN
        return RiskLevel.aggregate(overlappedIngredients(avoidedCodes).map { it.riskLevel() })
    }

    fun overlappedIngredients(avoidedCodes: Set<String>): List<FoodIngredient> {
        if (!isReady()) return emptyList()
        return ingredients.orEmpty().filter { it.code in avoidedCodes }
    }

    private fun localizedName(): LocalizedText =
        LocalizedText(korean = displayName.ifBlank { koreanName }, translations = resolveLangs(nameTranslations))

    private fun localizedDescription(): LocalizedText =
        LocalizedText(korean = description, translations = resolveLangs(descriptionTranslations))

    companion object {
        private val log = LoggerFactory.getLogger(Food::class.java)

        const val PLACEHOLDER_DESCRIPTION = "설명 준비 중"

        const val MAX_LONG_DESCRIPTION_LENGTH = 1000

        const val SPICINESS_UNASSESSED = -1

        const val MAX_REJECTION_REASON_LINES = 10

        const val MAX_REJECTION_REASON_LENGTH = 1000

        fun failed(koreanName: String, displayName: String = koreanName): Food {
            require(koreanName.isNotBlank()) { "food.koreanName 은 blank 일 수 없습니다" }
            require(displayName.isNotBlank()) { "food.displayName 은 blank 일 수 없습니다" }
            require(koreanName.length <= KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH) {
                "food.koreanName 은 ${KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH}자를 넘을 수 없습니다"
            }
            require(displayName.length <= KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH) {
                "food.displayName 은 ${KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH}자를 넘을 수 없습니다"
            }
            return Food(
                koreanName = koreanName,
                displayName = displayName,
                description = PLACEHOLDER_DESCRIPTION,
                spiciness = SPICINESS_UNASSESSED,
                contentStatus = FoodContentStatus.FAILED,
                ingredients = null,
            )
        }

        private fun resolveLangs(raw: Map<String, String>): Map<LanguageCode, String> =
            raw.mapNotNull { (key, value) ->
                LanguageCode.entries.firstOrNull { it.code == key }?.let { it to value }
            }.toMap()
    }
}
