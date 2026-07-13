package com.kbap.domain.food

import com.kbap.core.lang.LanguageCode
import com.kbap.core.lang.LocalizedText
import com.kbap.core.menu.KoreanMenuNameNormalizer
import com.kbap.core.persistence.BaseEntity
import com.kbap.core.risk.RiskLevel
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.BatchSize
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

    @Column(
        name = "korean_match_key",
        insertable = false,
        updatable = false,
        columnDefinition = "VARCHAR(255) GENERATED ALWAYS AS (REGEXP_REPLACE(korean_name COLLATE utf8mb4_bin, '[^가-힣]', '')) STORED",
    )
    var koreanMatchKey: String = "",

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
    @Column(name = "content_status", nullable = false, columnDefinition = "ENUM('INCOMPLETE','READY')")
    var contentStatus: FoodContentStatus = FoodContentStatus.READY,

    // 연관 성분은 읽기 전용 매핑 — 쓰기는 FoodAvoidanceSubstanceJpaRepository 를 통해서만 한다.
    @OneToMany(fetch = FetchType.EAGER, cascade = [])
    @JoinColumn(name = "food_id", insertable = false, updatable = false)
    @BatchSize(size = 100)
    var avoidanceSubstances: List<FoodAvoidanceSubstance> = emptyList(),
) : BaseEntity() {
    fun isReady(): Boolean = contentStatus == FoodContentStatus.READY

    fun koreanName(): String = koreanName

    fun displayName(lang: LanguageCode): String = localizedName().resolve(lang)

    fun description(lang: LanguageCode): String = localizedDescription().resolve(lang)

    fun avoidanceSubstancesByProbability(): List<FoodAvoidanceSubstance> =
        avoidanceSubstances.sortedByDescending { it.inclusionPercent }

    fun overallRisk(avoidedCodes: Set<String>): RiskLevel {
        if (!isReady()) return RiskLevel.UNKNOWN
        val targeted = avoidanceSubstances.filter { it.substanceCode in avoidedCodes }
        return RiskLevel.aggregate(targeted.map { it.riskLevel() })
    }

    private fun localizedName(): LocalizedText =
        LocalizedText(korean = koreanName, translations = resolveLangs(nameTranslations))

    private fun localizedDescription(): LocalizedText =
        LocalizedText(korean = description, translations = resolveLangs(descriptionTranslations))

    companion object {
        const val PLACEHOLDER_DESCRIPTION = "설명 준비 중"

        fun incomplete(koreanName: String): Food {
            require(koreanName.isNotBlank()) { "food.koreanName 은 blank 일 수 없습니다" }
            require(koreanName.length <= KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH) {
                "food.koreanName 은 ${KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH}자를 넘을 수 없습니다"
            }
            return Food(
                koreanName = koreanName,
                description = PLACEHOLDER_DESCRIPTION,
                spiciness = 0,
                contentStatus = FoodContentStatus.INCOMPLETE,
            )
        }

        private fun resolveLangs(raw: Map<String, String>): Map<LanguageCode, String> =
            raw.mapNotNull { (key, value) ->
                LanguageCode.entries.firstOrNull { it.code == key }?.let { it to value }
            }.toMap()
    }
}
