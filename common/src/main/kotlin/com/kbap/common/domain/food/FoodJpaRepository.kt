package com.kbap.common.domain.food

import com.kbap.common.domain.DailyCount
import com.kbap.common.domain.food.dto.FoodStatusCount
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

interface FoodJpaRepository : JpaRepository<Food, Long>, FoodRepositoryCustom {
    @Query(
        """
        select new com.kbap.common.domain.food.dto.FoodStatusCount(f.contentStatus, count(f))
        from Food f
        group by f.contentStatus
        """,
    )
    fun countGroupByContentStatus(): List<FoodStatusCount>

    @Query(
        """
        select new com.kbap.common.domain.DailyCount(cast(f.createdAt as LocalDate), count(f))
        from Food f
        where f.createdAt >= :from
        group by cast(f.createdAt as LocalDate)
        """,
    )
    fun countDailyCreatedSince(@Param("from") from: LocalDateTime): List<DailyCount>

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        """
        update Food f
        set f.contentStatus = com.kbap.common.domain.food.model.FoodContentStatus.PENDING_IMAGE,
            f.updatedAt = current_timestamp,
            f.version = f.version + 1
        where f.id in :ids
          and f.contentStatus = com.kbap.common.domain.food.model.FoodContentStatus.INCOMPLETE
          and (f.imageRef is null or f.imageRef = '')
        """,
    )
    fun markPendingImageByIdIn(@Param("ids") ids: List<Long>): Int

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        """
        update Food f
        set f.contentStatus = com.kbap.common.domain.food.model.FoodContentStatus.PENDING_REVIEW,
            f.updatedAt = current_timestamp,
            f.version = f.version + 1
        where f.id in :ids
          and f.contentStatus in (
            com.kbap.common.domain.food.model.FoodContentStatus.INCOMPLETE,
            com.kbap.common.domain.food.model.FoodContentStatus.PENDING_IMAGE
          )
        """,
    )
    fun markPendingReviewByIdIn(@Param("ids") ids: List<Long>): Int

    @Query(
        """
        select f from Food f
        where f.contentStatus = 'INCOMPLETE'
          and (:afterId is null or f.id > :afterId)
        order by f.id asc
        """,
    )
    fun findIncompleteAfter(@Param("afterId") afterId: Long?, pageable: Pageable): List<Food>

    fun findByContentStatusOrderByIdAsc(contentStatus: FoodContentStatus, pageable: Pageable): List<Food>

    fun findByKoreanNameIn(koreanNames: Set<String>): List<Food>

    @Query(
        """
        select f from Food f
        where (:contentStatus is null or f.contentStatus = :contentStatus)
          and (
            :matchKeyword is null
            or f.koreanName like concat('%', :matchKeyword, '%')
            or f.displayName like concat('%', :rawKeyword, '%')
          )
        """,
    )
    fun searchAdminFoodPage(
        @Param("matchKeyword") matchKeyword: String?,
        @Param("rawKeyword") rawKeyword: String?,
        @Param("contentStatus") contentStatus: FoodContentStatus?,
        pageable: Pageable,
    ): Page<Food>

    @Query(
        """
        select f from Food f
        where (f.imageRef is null or f.imageRef = '')
          and not exists (
            select 1 from ImageBatchItem i
            where i.foodId = f.id and i.itemStatus = com.kbap.common.domain.food.model.ImageBatchItemStatus.PENDING
          )
        order by f.id asc
        """,
    )
    fun findImageCandidates(): List<Food>

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
            f.korean_name collate utf8mb4_unicode_ci like concat('%', :matchKw, '%') escape '\\'
            or f.display_name collate utf8mb4_unicode_ci like concat('%', :rawKw, '%') escape '\\'
            or (
              :jsonPath is not null
              and json_unquote(json_extract(f.name_translations, :jsonPath)) collate utf8mb4_unicode_ci
                like concat('%', :rawKw, '%') escape '\\'
            )
          )
        order by f.id desc
        limit :size
        """,
    )
    fun searchFoodPageIds(
        @Param("matchKw") matchKeyword: String,
        @Param("rawKw") rawKeyword: String,
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
