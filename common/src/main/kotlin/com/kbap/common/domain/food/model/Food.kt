package com.kbap.common.domain.food.model

import com.kbap.common.domain.BaseEntity
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.LocalizedText
import com.kbap.common.domain.Spiciness
import com.kbap.common.util.KoreanMenuNameNormalizer
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(
    name = "food",
    uniqueConstraints = [UniqueConstraint(name = "uq_food_korean_name", columnNames = ["korean_name"])],
)
class Food(
    // 중복 방지 match key — KoreanMenuNameNormalizer.matchKey 결과를 유지한다(표시용 아님)
    @Column(name = "korean_name", nullable = false, length = 255)
    var koreanName: String = "",

    @Column(name = "display_name", nullable = false, length = 255)
    var displayName: String = koreanName,

    @Column(name = "image_ref", length = 500)
    var imageRef: String? = null,

    @Column(name = "description", nullable = false, length = 255)
    var description: String = "",

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
        columnDefinition = "ENUM('INCOMPLETE','PENDING_IMAGE','PENDING_REVIEW','REVIEWED','REVIEW_REJECTED','READY')",
    )
    var contentStatus: FoodContentStatus = FoodContentStatus.READY,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "avoidance_substances")
    var avoidanceSubstances: List<FoodAvoidanceItem>? = emptyList(),

    @Column(name = "content_review_attempts", nullable = false, columnDefinition = "int not null default 0")
    var contentReviewAttempts: Int = 0,

    @Column(name = "content_review_rejection_reason", length = MAX_REJECTION_REASON_LENGTH)
    var contentReviewRejectionReason: String? = null,
) : BaseEntity() {
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false, columnDefinition = "bigint not null default 0")
    var version: Long = 0

    fun isReady(): Boolean = contentStatus == FoodContentStatus.READY

    fun needsImage(): Boolean = imageRef.isNullOrBlank()

    fun needsDescription(): Boolean = description.isBlank() || description == PLACEHOLDER_DESCRIPTION

    fun needsNameTranslations(): Boolean = !hasAllTargetTranslations(nameTranslations)

    fun needsDescriptionTranslations(): Boolean = !hasAllTargetTranslations(descriptionTranslations)

    private fun hasAllTargetTranslations(translations: Map<String, String>): Boolean =
        TARGET_LANG_CODES.all { !translations[it].isNullOrBlank() }

    fun needsAvoidanceMapping(): Boolean = avoidanceSubstances == null

    fun needsAvoidanceAssessment(): Boolean =
        avoidanceSubstances == null || spiciness == SPICINESS_UNASSESSED

    fun updateNameTranslations(translations: Map<String, String>) {
        this.nameTranslations = translations
    }

    fun updateDescription(description: String, translations: Map<String, String>) {
        this.description = description
        this.descriptionTranslations = translations
    }

    fun assessAvoidance(substances: List<FoodAvoidanceItem>, spiciness: Int) {
        require(spiciness in Spiciness.RANGE) {
            "spiciness 는 ${Spiciness.RANGE} 여야 합니다: $spiciness"
        }
        this.avoidanceSubstances = substances
        this.spiciness = spiciness
    }

    fun passContentReview() {
        if (contentStatus == FoodContentStatus.REVIEWED) return
        require(contentStatus == FoodContentStatus.PENDING_REVIEW) {
            "검수 대상(PENDING_REVIEW)이 아닙니다: $contentStatus"
        }
        contentStatus = FoodContentStatus.REVIEWED
    }

    fun rejectContentReview(rejectedFields: Set<FoodContentReviewField>, reason: String?) {
        require(contentStatus == FoodContentStatus.PENDING_REVIEW) {
            "검수 대상(PENDING_REVIEW)이 아닙니다: $contentStatus"
        }
        require(rejectedFields.isNotEmpty()) { "탈락 결과에는 문제 필드가 최소 1개 있어야 합니다" }
        if (contentReviewAttempts >= MAX_CONTENT_REVIEW_ATTEMPTS) {
            contentStatus = FoodContentStatus.REVIEW_REJECTED
            contentReviewRejectionReason = reason
                ?.lineSequence()
                ?.take(MAX_REJECTION_REASON_LINES)
                ?.joinToString("\n")
                ?.take(MAX_REJECTION_REASON_LENGTH)
            return
        }
        rejectedFields.forEach(::clearField)
        contentReviewAttempts++
        contentStatus = FoodContentStatus.INCOMPLETE
        transitionByContentState()
    }

    private fun clearField(field: FoodContentReviewField) {
        when (field) {
            FoodContentReviewField.DESCRIPTION -> description = PLACEHOLDER_DESCRIPTION
            FoodContentReviewField.NAME_TRANSLATIONS -> nameTranslations = emptyMap()
            FoodContentReviewField.DESCRIPTION_TRANSLATIONS -> descriptionTranslations = emptyMap()
            FoodContentReviewField.AVOIDANCE_SUBSTANCES -> avoidanceSubstances = null
            FoodContentReviewField.SPICINESS -> spiciness = SPICINESS_UNASSESSED
            FoodContentReviewField.IMAGE -> imageRef = null
        }
    }

    fun transitionByContentState(): FoodContentStatus {
        if (contentStatus in TERMINAL_CONTENT_STATUSES) {
            return contentStatus
        }
        val textComplete = !needsDescription() &&
            !needsNameTranslations() &&
            !needsDescriptionTranslations() &&
            !needsAvoidanceMapping() &&
            spiciness != SPICINESS_UNASSESSED
        contentStatus = when {
            !textComplete -> FoodContentStatus.INCOMPLETE
            needsImage() -> FoodContentStatus.PENDING_IMAGE
            else -> FoodContentStatus.PENDING_REVIEW
        }
        return contentStatus
    }

    fun attachImage(imageRef: String) {
        require(imageRef.isNotBlank()) { "imageRef 는 blank 일 수 없습니다" }
        this.imageRef = imageRef
        transitionByContentState()
    }

    fun displayName(lang: LanguageCode): String = localizedName().resolve(lang)

    fun description(lang: LanguageCode): String = localizedDescription().resolve(lang)

    fun avoidanceSubstancesByProbability(): List<FoodAvoidanceItem> =
        avoidanceSubstances.orEmpty().sortedByDescending { it.inclusionPercent }

    fun overallRisk(avoidedCodes: Set<String>): RiskLevel {
        if (!isReady()) return RiskLevel.UNKNOWN
        // 미조사(null)를 SAFE 로 은폐하지 않는다 — 안전 직결이라 fail-closed.
        val substances = avoidanceSubstances ?: return RiskLevel.UNKNOWN
        val targeted = substances.filter { it.code in avoidedCodes }
        return RiskLevel.aggregate(targeted.map { it.riskLevel() })
    }

    private fun localizedName(): LocalizedText =
        LocalizedText(korean = displayName.ifBlank { koreanName }, translations = resolveLangs(nameTranslations))

    private fun localizedDescription(): LocalizedText =
        LocalizedText(korean = description, translations = resolveLangs(descriptionTranslations))

    companion object {
        const val PLACEHOLDER_DESCRIPTION = "설명 준비 중"

        const val SPICINESS_UNASSESSED = -1

        const val MAX_CONTENT_REVIEW_ATTEMPTS = 2

        const val MAX_REJECTION_REASON_LINES = 10

        const val MAX_REJECTION_REASON_LENGTH = 1000

        // 콘텐츠 채움 배치가 되돌리면 안 되는 상태 — 검수·승인 단계는 사람/AI 판정이 소유한다.
        private val TERMINAL_CONTENT_STATUSES = setOf(
            FoodContentStatus.PENDING_REVIEW,
            FoodContentStatus.REVIEWED,
            FoodContentStatus.REVIEW_REJECTED,
            FoodContentStatus.READY,
        )

        // READY 완비 판정 기준 — ko 원문 제외 9개 대상 언어(헌법 V 사전 번역 정책).
        private val TARGET_LANG_CODES: Set<String> =
            LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }.toSet()

        fun incomplete(koreanName: String, displayName: String = koreanName): Food {
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
                contentStatus = FoodContentStatus.INCOMPLETE,
                avoidanceSubstances = null,
            )
        }

        private fun resolveLangs(raw: Map<String, String>): Map<LanguageCode, String> =
            raw.mapNotNull { (key, value) ->
                LanguageCode.entries.firstOrNull { it.code == key }?.let { it to value }
            }.toMap()
    }
}
