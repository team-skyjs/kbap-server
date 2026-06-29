package com.meogo.api.persistence.food

import com.meogo.api.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "food_description_translation")
class FoodDescriptionTranslationJpaEntity(
    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,

    @Column(name = "kind", nullable = false, length = 10)
    var kind: String = "",

    @Column(name = "lang_code", nullable = false, length = 10)
    var langCode: String = "",

    @Column(name = "content", nullable = false, length = 1024)
    var content: String = "",
) : BaseEntity()
