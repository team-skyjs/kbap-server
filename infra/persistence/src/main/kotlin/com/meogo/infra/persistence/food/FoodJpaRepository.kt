package com.meogo.infra.persistence.food

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodJpaRepository : JpaRepository<FoodJpaEntity, Long> {
    @Query(
        """
        select distinct f from FoodJpaEntity f
        left join fetch f.foodAvoidanceSubstances
        where f.koreanName = :koreanName
        """,
    )
    fun findByKoreanNameWithAvoidanceSubstances(@Param("koreanName") koreanName: String): FoodJpaEntity?
}
