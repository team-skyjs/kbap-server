package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "avoidance_substance")
class AvoidanceSubstanceJpaEntity(
    @Column(name = "code", nullable = false, length = 40)
    var code: String = "",

    @Column(name = "korean_name", nullable = false, length = 100)
    var koreanName: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "translations", nullable = false)
    var translations: Map<String, String> = emptyMap(),
) : BaseEntity() {
    fun toDomain(): AvoidanceSubstance =
        AvoidanceSubstance.reconstitute(
            id = id,
            code = AvoidanceSubstanceCode.valueOf(code),
            koreanName = koreanName,
            translations = resolveTranslations(),
        )

    private fun resolveTranslations(): Map<LanguageCode, String> =
        translations.mapNotNull { (key, value) ->
            LanguageCode.entries.firstOrNull { it.code == key }?.let { it to value }
        }.toMap()
}
