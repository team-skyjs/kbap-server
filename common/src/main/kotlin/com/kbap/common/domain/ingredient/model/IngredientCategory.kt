package com.kbap.common.domain.ingredient.model

import com.kbap.common.domain.BaseEntity
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.LocalizedText
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "ingredient_category")
class IngredientCategory(
    @Column(name = "code", nullable = false, length = 40)
    var code: String = "",

    @Column(name = "korean_name", nullable = false, length = 100)
    var koreanName: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "translations", nullable = false)
    var translations: Map<String, String> = emptyMap(),

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity() {
    fun displayName(lang: LanguageCode): String =
        LocalizedText(korean = koreanName, translations = resolveTranslations()).resolve(lang)

    private fun resolveTranslations(): Map<LanguageCode, String> =
        translations.mapNotNull { (key, value) ->
            LanguageCode.entries.firstOrNull { it.code == key }?.let { it to value }
        }.toMap()
}
