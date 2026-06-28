package com.meogo.api.persistence.food

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodJpaRepository : JpaRepository<FoodJpaEntity, Long> {
    @Query(
        """
        select distinct f from FoodJpaEntity f
        left join fetch f.translations
        left join fetch f.foodIngredients fi
        left join fetch fi.ingredient i
        left join fetch i.translations
        where f.koreanName = :koreanName
        """,
    )
    fun findByKoreanNameWithGraph(@Param("koreanName") koreanName: String): FoodJpaEntity?
}
