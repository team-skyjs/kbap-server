package com.meogo.infra.persistence.food

import com.meogo.infra.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "food_name_translation")
class FoodNameTranslationJpaEntity(
    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,

    @Column(name = "lang_code", nullable = false, length = 10)
    var langCode: String = "",

    @Column(name = "name", nullable = false, length = 255)
    var name: String = "",
) : BaseEntity()
