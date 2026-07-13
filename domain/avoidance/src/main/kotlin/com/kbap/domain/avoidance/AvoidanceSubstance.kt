package com.kbap.domain.avoidance

import com.kbap.core.lang.LanguageCode
import com.kbap.core.lang.LocalizedText
import com.kbap.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "avoidance_substance")
class AvoidanceSubstance(
    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, length = 40)
    var code: AvoidanceSubstanceCode = AvoidanceSubstanceCode.EGG,

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
