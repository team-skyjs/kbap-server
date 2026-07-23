package com.kbap.domain.food

import com.kbap.domain.food.model.Food
import com.kbap.domain.food.model.FoodContentStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface FoodJpaRepository : JpaRepository<Food, Long>, FoodJpaRepositoryCustom {
    // 벌크 상태 전환 — 단일 UPDATE 문. 영속성 컨텍스트를 우회하므로 detached 상태의 배치 writer 전용.
    // updatedAt 은 @UpdateTimestamp 가 안 타서 직접 갱신한다.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        """
        update Food f
        set f.contentStatus = :status, f.updatedAt = current_timestamp
        where f.id in :ids
        """,
    )
    fun updateContentStatusByIdIn(
        @Param("ids") ids: List<Long>,
        @Param("status") status: FoodContentStatus,
    ): Int

    @Query(
        """
        select f from Food f
        where f.contentStatus = 'INCOMPLETE'
          and (:afterId is null or f.id > :afterId)
        order by f.id asc
        """,
    )
    fun findIncompleteAfter(@Param("afterId") afterId: Long?, pageable: Pageable): List<Food>

    fun findByKoreanNameIn(koreanNames: Set<String>): List<Food>

    @Query(
        """
        select f.id from Food f
        where (:cursor is null or f.id < :cursor)
          and f.contentStatus = 'READY'
        order by f.id desc
        """,
    )
    fun findFoodPageIds(@Param("cursor") cursor: Long?, pageable: Pageable): List<Long>

    fun findByIdIn(ids: List<Long>): List<Food>

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
