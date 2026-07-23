package com.kbap.domain.food

import com.kbap.domain.food.model.Food
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface FoodJpaRepository : JpaRepository<Food, Long>, FoodJpaRepositoryCustom {
    // 벌크 상태 전환(배치 writer 전용) — 단일 UPDATE 문, 영속성 컨텍스트 우회. updatedAt 은
    // @UpdateTimestamp 가 안 타서 직접 갱신하고, version 을 올려 병행 세션의 stale save 를 무효화한다.
    // 가드: writer 스냅샷이 낡았을 수 있다 — 이미지가 그 사이 도착한 음식을 TEXT_READY 로 후퇴시키지
    // 않고(INCOMPLETE + 무이미지만), 미적용 건은 다음 배치 실행이 최신 상태로 수렴한다(KB-226).
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        """
        update Food f
        set f.contentStatus = com.kbap.domain.food.model.FoodContentStatus.TEXT_READY,
            f.updatedAt = current_timestamp,
            f.version = f.version + 1
        where f.id in :ids
          and f.contentStatus = com.kbap.domain.food.model.FoodContentStatus.INCOMPLETE
          and (f.imageRef is null or f.imageRef = '')
        """,
    )
    fun markTextReadyByIdIn(@Param("ids") ids: List<Long>): Int

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        """
        update Food f
        set f.contentStatus = com.kbap.domain.food.model.FoodContentStatus.PENDING_REVIEW,
            f.updatedAt = current_timestamp,
            f.version = f.version + 1
        where f.id in :ids
          and f.contentStatus in (
            com.kbap.domain.food.model.FoodContentStatus.INCOMPLETE,
            com.kbap.domain.food.model.FoodContentStatus.TEXT_READY
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

    fun findByKoreanNameIn(koreanNames: Set<String>): List<Food>

    // 이미지 제출 후보(KB-226) — 상태값으로 거르지 않는다. "이미지가 필요한가"의 진실은 imageRef 하나이고,
    // PENDING item 미포함 조건이 중복 제출 가드를 겸한다(버튼 연타 무해).
    @Query(
        """
        select f from Food f
        where (f.imageRef is null or f.imageRef = '')
          and not exists (
            select 1 from ImageBatchItem i
            where i.foodId = f.id and i.itemStatus = com.kbap.domain.food.model.ImageBatchItemStatus.PENDING
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
