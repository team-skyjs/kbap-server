package com.meogo.infra.persistence.food

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodJpaRepository : JpaRepository<FoodJpaEntity, Long> {
    @Query("select f.id from FoodJpaEntity f order by f.id asc")
    fun findFoodIds(pageable: Pageable): List<Long>

    @Query(
        """
        select distinct f from FoodJpaEntity f
        left join fetch f.foodAvoidanceSubstances
        where f.koreanMatchKey in :keys
        order by f.id asc
        """,
    )
    fun findByKoreanMatchKeyInWithAvoidanceSubstances(@Param("keys") keys: Set<String>): List<FoodJpaEntity>

    @Query(
        """
        select distinct f from FoodJpaEntity f
        left join fetch f.foodAvoidanceSubstances
        where f.koreanName in :koreanNames
        """,
    )
    fun findByKoreanNameIn(@Param("koreanNames") koreanNames: Set<String>): List<FoodJpaEntity>

    @Query(
        """
        select f.id from FoodJpaEntity f
        where (:cursor is null or f.id < :cursor)
          and f.contentStatus = 'READY'
        order by f.id desc
        """,
    )
    fun findFoodPageIds(@Param("cursor") cursor: Long?, pageable: Pageable): List<Long>

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

    @Query(
        nativeQuery = true,
        value = """
        select f.id from food f
        where f.status = 'ACTIVE'
          and f.content_status = 'READY'
          and (:cursor is null or f.id < :cursor)
          and (
            f.korean_name collate utf8mb4_unicode_ci like concat('%', :kw, '%') escape '\\'
            or (
              :jsonPath is not null
              and json_unquote(json_extract(f.name_translations, :jsonPath)) collate utf8mb4_unicode_ci
                like concat('%', :kw, '%') escape '\\'
            )
          )
        order by f.id desc
        limit :size
        """,
    )
    fun searchFoodPageIds(
        @Param("kw") keyword: String,
        @Param("jsonPath") jsonPath: String?,
        @Param("cursor") cursor: Long?,
        @Param("size") size: Int,
    ): List<Long>

    @Query(
        nativeQuery = true,
        value = """
        select f.id from food f
        where f.status = 'ACTIVE'
          and f.content_status = 'READY'
        order by rand()
        limit :size
        """,
    )
    fun findRandomReadyIds(@Param("size") size: Int): List<Long>
}
