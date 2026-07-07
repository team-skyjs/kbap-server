package com.meogo.infra.persistence.research

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.research.candidate.FoodCandidate
import com.meogo.core.research.candidate.SubstanceSnapshot
import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(
    name = "food_candidate",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_food_candidate_korean_name", columnNames = ["korean_name"]),
    ],
)
class FoodCandidateJpaEntity(
    @Column(name = "korean_name", nullable = false, length = 255)
    var koreanName: String = "",

    @Column(name = "description", length = 255)
    var description: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "description_translations", nullable = false)
    var descriptionTranslations: Map<String, String> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "substance_mapping", nullable = false)
    var substanceMapping: List<SubstanceMappingJson> = emptyList(),

    @Column(name = "published_food_id")
    var publishedFoodId: Long? = null,
) : BaseEntity() {
    fun toDomain(): FoodCandidate =
        FoodCandidate.reconstitute(
            id = id,
            koreanName = koreanName,
            koreanDescription = description,
            descriptionTranslations = descriptionTranslations.mapNotNull { (key, value) ->
                LanguageCode.entries.firstOrNull { it.code == key }?.let { it to value }
            }.toMap(),
            substanceMapping = substanceMapping.map { SubstanceSnapshot(it.code, it.percent) },
            publishedFoodId = publishedFoodId,
        )

    companion object {
        fun from(domain: FoodCandidate): FoodCandidateJpaEntity =
            FoodCandidateJpaEntity(
                koreanName = domain.koreanName,
                description = domain.koreanDescription,
                descriptionTranslations = domain.descriptionTranslations.entries.associate { it.key.code to it.value },
                substanceMapping = domain.substanceMapping.map { SubstanceMappingJson(it.code, it.inclusionPercent) },
                publishedFoodId = domain.publishedFoodId,
            )
    }
}

data class SubstanceMappingJson(
    val code: String = "",
    val percent: Int = 0,
)
