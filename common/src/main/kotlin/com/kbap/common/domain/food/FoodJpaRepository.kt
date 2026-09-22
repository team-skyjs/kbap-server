package com.kbap.common.domain.food

import com.kbap.common.domain.DailyCount
import com.kbap.common.domain.food.dto.FoodStatusCount
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

interface FoodJpaRepository : JpaRepository<Food, Long>, FoodRepositoryCustom {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Food f where f.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Food?

    @Query(
        value = "SELECT * FROM food WHERE status = 'DELETED' ORDER BY updated_at DESC, id DESC",
        countQuery = "SELECT count(*) FROM food WHERE status = 'DELETED'",
        nativeQuery = true,
    )
    fun findDeletedPage(pageable: Pageable): Page<Food>

    @Query(value = "SELECT * FROM food WHERE id = :id AND status = 'DELETED'", nativeQuery = true)
    fun findDeletedById(@Param("id") id: Long): Food?

    @Query(value = "SELECT * FROM food WHERE id = :id", nativeQuery = true)
    fun findAnyById(@Param("id") id: Long): Food?

    @Query(
        value = """
            SELECT * FROM food
            WHERE status = :entityStatus
              AND (:contentStatus IS NULL OR content_status = :contentStatus)
              AND (:failureKind IS NULL OR content_failure_kind = :failureKind)
              AND (:keyword IS NULL OR display_name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')
              AND (:ingredientCode IS NULL OR id IN (
                    SELECT fi.food_id FROM food_ingredient fi
                    JOIN ingredients i ON i.id = fi.ingredient_id
                    WHERE i.code = :ingredientCode))
              AND (:categoryCode IS NULL OR id IN (
                    SELECT fi.food_id FROM food_ingredient fi
                    JOIN ingredients i ON i.id = fi.ingredient_id
                    JOIN ingredient_category c ON c.id = i.category_id
                    WHERE c.code = :categoryCode))
            ORDER BY id DESC
        """,
        countQuery = """
            SELECT count(*) FROM food
            WHERE status = :entityStatus
              AND (:contentStatus IS NULL OR content_status = :contentStatus)
              AND (:failureKind IS NULL OR content_failure_kind = :failureKind)
              AND (:keyword IS NULL OR display_name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')
              AND (:ingredientCode IS NULL OR id IN (
                    SELECT fi.food_id FROM food_ingredient fi
                    JOIN ingredients i ON i.id = fi.ingredient_id
                    WHERE i.code = :ingredientCode))
              AND (:categoryCode IS NULL OR id IN (
                    SELECT fi.food_id FROM food_ingredient fi
                    JOIN ingredients i ON i.id = fi.ingredient_id
                    JOIN ingredient_category c ON c.id = i.category_id
                    WHERE c.code = :categoryCode))
        """,
        nativeQuery = true,
    )
    fun searchAdminFoodPage(
        @Param("entityStatus") entityStatus: String,
        @Param("contentStatus") contentStatus: String?,
        @Param("failureKind") failureKind: String?,
        @Param("keyword") keyword: String?,
        @Param("ingredientCode") ingredientCode: String?,
        @Param("categoryCode") categoryCode: String?,
        pageable: Pageable,
    ): Page<Food>

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

    fun findByContentStatusOrderByIdAsc(contentStatus: FoodContentStatus, pageable: Pageable): List<Food>

    @Query(
        """
        select f.id
        from Food f
        where f.contentStatus = com.kbap.common.domain.food.model.FoodContentStatus.READY
          and not exists (
            select 1
            from FoodVectorOutbox o
            where o.foodId = f.id
              and o.operation = com.kbap.common.domain.food.model.FoodVectorOutboxOperation.UPSERT
          )
        order by f.id asc
        """,
    )
    fun findReadyIdsWithoutVectorUpsertOutbox(pageable: Pageable): List<Long>

    @Query(
        """
        select count(f)
        from Food f
        where f.contentStatus = com.kbap.common.domain.food.model.FoodContentStatus.READY
          and not exists (
            select 1
            from FoodVectorOutbox o
            where o.foodId = f.id
              and o.operation = com.kbap.common.domain.food.model.FoodVectorOutboxOperation.UPSERT
          )
        """,
    )
    fun countReadyWithoutVectorUpsertOutbox(): Long

    fun findByKoreanNameIn(koreanNames: Set<String>): List<Food>

    fun findByDisplayNameContaining(displayName: String, pageable: Pageable): Page<Food>

    fun findByContentStatus(contentStatus: FoodContentStatus, pageable: Pageable): Page<Food>

    fun findByDisplayNameContainingAndContentStatus(
        displayName: String,
        contentStatus: FoodContentStatus,
        pageable: Pageable,
    ): Page<Food>

    fun findByDisplayNameContainingOrderByIdAsc(displayName: String): List<Food>

    fun findByContentStatusAndDisplayNameContainingOrderByIdAsc(
        contentStatus: FoodContentStatus,
        displayName: String,
    ): List<Food>

    fun countByDisplayNameContaining(displayName: String): Long

    fun countByContentStatus(contentStatus: FoodContentStatus): Long

    fun countByDisplayNameContainingAndContentStatus(displayName: String, contentStatus: FoodContentStatus): Long

    @Query("select f from Food f where $IMAGE_CANDIDATE order by f.id asc")
    fun findImageCandidates(): List<Food>

    @Query("select count(f) from Food f where $IMAGE_CANDIDATE")
    fun countImageCandidates(): Long

    @Query("select count(f) from Food f where $IMAGE_CANDIDATE and $FAILED_REGENERATION")
    fun countStrandedImageRegenerations(): Long

    @Query(
        """
        select f from Food f
        where f.id in :ids
          and f.contentStatus = com.kbap.common.domain.food.model.FoodContentStatus.PENDING_IMAGE
          and not exists (
            select 1 from ImageBatchItem i
            where i.foodId = f.id and i.itemStatus = com.kbap.common.domain.food.model.ImageBatchItemStatus.PENDING
          )
        order by f.id asc
        """,
    )
    fun findImageCandidatesByIdIn(@Param("ids") ids: List<Long>): List<Food>

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
            f.display_name collate utf8mb4_unicode_ci like concat('%', :kw, '%') escape '\\'
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

    companion object {
        const val IMAGE_CANDIDATE =
            "f.contentStatus = com.kbap.common.domain.food.model.FoodContentStatus.PENDING_IMAGE " +
                "and not exists (select 1 from ImageBatchItem i where i.foodId = f.id " +
                "and i.itemStatus = com.kbap.common.domain.food.model.ImageBatchItemStatus.PENDING)"

        const val FAILED_REGENERATION =
            "f.contentStatus = com.kbap.common.domain.food.model.FoodContentStatus.PENDING_IMAGE " +
                "and f.imageRef is not null and trim(f.imageRef) <> ''"
    }
}
