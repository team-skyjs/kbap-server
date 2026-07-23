package com.kbap.domain.food.model

import com.kbap.core.food.FoodAvoidanceAssessmentResult
import com.kbap.core.lang.LanguageCode
import com.kbap.core.lang.LocalizedText
import com.kbap.core.menu.KoreanMenuNameNormalizer
import com.kbap.core.persistence.BaseEntity
import com.kbap.core.risk.RiskLevel
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
    @Column(name = "korean_name", nullable = false, length = 255)
    var koreanName: String = "",

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
    @Column(name = "content_status", nullable = false, columnDefinition = "ENUM('INCOMPLETE','PENDING_REVIEW','READY')")
    var contentStatus: FoodContentStatus = FoodContentStatus.READY,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "avoidance_substances")
    var avoidanceSubstances: List<FoodAvoidanceItem>? = emptyList(),
) : BaseEntity() {
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
        require(spiciness in FoodAvoidanceAssessmentResult.SPICINESS_RANGE) {
            "spiciness 는 ${FoodAvoidanceAssessmentResult.SPICINESS_RANGE} 여야 합니다: $spiciness"
        }
        this.avoidanceSubstances = substances
        this.spiciness = spiciness
    }

    fun transitionToPendingReviewIfComplete(): Boolean {
        if (contentStatus != FoodContentStatus.INCOMPLETE) return true
        val complete = !needsImage() &&
            !needsDescription() &&
            !needsNameTranslations() &&
            !needsDescriptionTranslations() &&
            !needsAvoidanceMapping() &&
            spiciness != SPICINESS_UNASSESSED
        if (complete) contentStatus = FoodContentStatus.PENDING_REVIEW
        return complete
    }

    fun koreanName(): String = koreanName

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
        LocalizedText(korean = koreanName, translations = resolveLangs(nameTranslations))

    private fun localizedDescription(): LocalizedText =
        LocalizedText(korean = description, translations = resolveLangs(descriptionTranslations))

    companion object {
        const val PLACEHOLDER_DESCRIPTION = "설명 준비 중"

        const val SPICINESS_UNASSESSED = -1

        // READY 완비 판정 기준 — ko 원문 제외 9개 대상 언어(헌법 V 사전 번역 정책).
        private val TARGET_LANG_CODES: Set<String> =
            LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }.toSet()

        fun incomplete(koreanName: String): Food {
            require(koreanName.isNotBlank()) { "food.koreanName 은 blank 일 수 없습니다" }
            require(koreanName.length <= KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH) {
                "food.koreanName 은 ${KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH}자를 넘을 수 없습니다"
            }
            return Food(
                koreanName = koreanName,
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
