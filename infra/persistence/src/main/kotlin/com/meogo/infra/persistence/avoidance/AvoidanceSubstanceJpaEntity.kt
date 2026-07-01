package com.meogo.infra.persistence.avoidance

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
) : BaseEntity()
