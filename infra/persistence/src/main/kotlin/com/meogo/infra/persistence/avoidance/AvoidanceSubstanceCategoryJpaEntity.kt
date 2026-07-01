package com.meogo.infra.persistence.avoidance

import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "avoidance_substance_category")
class AvoidanceSubstanceCategoryJpaEntity(
    @Column(name = "substance_id", nullable = false)
    var substanceId: Long = 0,

    @Column(name = "category", nullable = false, length = 30)
    var category: String = "",
) : BaseEntity()
