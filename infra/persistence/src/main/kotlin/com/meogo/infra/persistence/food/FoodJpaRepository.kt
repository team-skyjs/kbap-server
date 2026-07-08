package com.meogo.infra.persistence.food

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodJpaRepository : JpaRepository<FoodJpaEntity, Long> {
    @Query("select f.id from FoodJpaEntity f order by f.id asc")
    fun findFoodIds(pageable: Pageable): List<Long>

    @Query("select f.id from FoodJpaEntity f where f.koreanMatchKey = :key order by f.id asc")
    fun findIdsByKoreanMatchKey(@Param("key") key: String): List<Long>

    @Query(
        """
        select f.id from FoodJpaEntity f
        where (:cursor is null or f.id < :cursor)
        order by f.id desc
        """,
    )
    fun findMenuPageIds(@Param("cursor") cursor: Long?, pageable: Pageable): List<Long>

    @Query(
        """
        select distinct f from FoodJpaEntity f
        left join fetch f.foodAvoidanceSubstances
        where f.id in :ids
        order by f.id desc
        """,
    )
    fun findByIdInWithAvoidanceSubstancesDesc(@Param("ids") ids: List<Long>): List<FoodJpaEntity>

    @Query(
        """
        select distinct f from FoodJpaEntity f
        left join fetch f.foodAvoidanceSubstances
        where f.id in :ids
        order by f.id asc
        """,
    )
    fun findByIdInWithAvoidanceSubstances(@Param("ids") ids: List<Long>): List<FoodJpaEntity>
}
