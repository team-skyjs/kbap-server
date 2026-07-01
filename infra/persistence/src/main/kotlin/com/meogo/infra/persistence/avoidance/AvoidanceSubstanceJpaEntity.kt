package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceCategory
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "avoidance_substance")
class AvoidanceSubstanceJpaEntity(
    @Column(name = "code", nullable = false, length = 40)
    var code: String = "",

    @Column(name = "korean_name", nullable = false, length = 100)
    var koreanName: String = "",

    @Column(name = "name_zh_hans", length = 100)
    var nameZhHans: String? = null,

    @Column(name = "name_en", length = 100)
    var nameEn: String? = null,

    @Column(name = "name_ja", length = 100)
    var nameJa: String? = null,

    @Column(name = "name_zh_hant", length = 100)
    var nameZhHant: String? = null,

    @Column(name = "name_vi", length = 100)
    var nameVi: String? = null,

    @Column(name = "name_id", length = 100)
    var nameId: String? = null,

    @Column(name = "name_th", length = 100)
    var nameTh: String? = null,

    @Column(name = "name_ru", length = 100)
    var nameRu: String? = null,

    @Column(name = "name_es", length = 100)
    var nameEs: String? = null,
) : BaseEntity() {
    fun toDomain(categories: Set<AvoidanceCategory>): AvoidanceSubstance =
        AvoidanceSubstance.reconstitute(
            id = id,
            code = AvoidanceSubstanceCode.valueOf(code),
            koreanName = koreanName,
            translations = translationColumns()
                .filterValues { !it.isNullOrBlank() }
                .mapValues { it.value!! },
            categories = categories,
        )

    private fun translationColumns(): Map<LanguageCode, String?> =
        mapOf(
            LanguageCode.ZH_HANS to nameZhHans,
            LanguageCode.EN to nameEn,
            LanguageCode.JA to nameJa,
            LanguageCode.ZH_HANT to nameZhHant,
            LanguageCode.VI to nameVi,
            LanguageCode.ID to nameId,
            LanguageCode.TH to nameTh,
            LanguageCode.RU to nameRu,
            LanguageCode.ES to nameEs,
        )
}
