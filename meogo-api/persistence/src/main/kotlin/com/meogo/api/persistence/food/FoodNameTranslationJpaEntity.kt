package com.meogo.api.persistence.food

import com.meogo.api.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "food_name_translation")
class FoodNameTranslationJpaEntity(
    @Column(name = "lang_code", nullable = false, length = 10)
    var langCode: String = "",

    @Column(name = "name", nullable = false, length = 255)
    var name: String = "",
) : BaseEntity()
