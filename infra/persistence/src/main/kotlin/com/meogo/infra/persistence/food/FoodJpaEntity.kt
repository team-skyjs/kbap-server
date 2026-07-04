package com.meogo.infra.persistence.food

import com.meogo.core.food.Food
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "food")
class FoodJpaEntity(
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

    @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "food_id", nullable = false)
    var foodAvoidanceSubstances: MutableSet<FoodAvoidanceSubstanceJpaEntity> = mutableSetOf(),
) : BaseEntity() {
    fun toDomain(): Food =
        Food.reconstitute(
            id = id,
            content = FoodContent(
                name = LocalizedText(korean = koreanName, translations = resolve(nameTranslations)),
                description = LocalizedText(korean = description, translations = resolve(descriptionTranslations)),
            ),
            imageRef = imageRef,
            spiciness = FoodSpiciness(spiciness),
            avoidanceSubstances = foodAvoidanceSubstances.map { it.toDomain() },
        )

    private fun resolve(raw: Map<String, String>): Map<LanguageCode, String> =
        raw.mapNotNull { (key, value) ->
            LanguageCode.entries.firstOrNull { it.code == key }?.let { it to value }
        }.toMap()
}
