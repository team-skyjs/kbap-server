package com.kbap.common.domain.ingredient.model

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.LocalizedText
import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "ingredients")
class Ingredient(
    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, length = 40)
    var code: IngredientCode = IngredientCode.EGG,

    @Column(name = "korean_name", nullable = false, length = 100)
    var koreanName: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "translations", nullable = false)
    var translations: Map<String, String> = emptyMap(),
) : BaseEntity() {
    fun displayName(lang: LanguageCode): String =
        LocalizedText(korean = koreanName, translations = resolveTranslations()).resolve(lang)

    private fun resolveTranslations(): Map<LanguageCode, String> =
        translations.mapNotNull { (key, value) ->
            LanguageCode.entries.firstOrNull { it.code == key }?.let { it to value }
        }.toMap()
}
