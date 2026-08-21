package com.kbap.common.domain.scan

import com.kbap.common.domain.DailyCount
import com.kbap.common.domain.scan.model.ScanHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ScanHistoryJpaRepository : JpaRepository<ScanHistory, Long> {
    fun existsByMemberIdAndFoodId(memberId: Long, foodId: Long): Boolean

    @Query(
        """
        select new com.kbap.common.domain.DailyCount(cast(sh.createdAt as LocalDate), count(sh))
        from ScanHistory sh
        where sh.createdAt >= :from
        group by cast(sh.createdAt as LocalDate)
        """,
    )
    fun countDailySince(@Param("from") from: LocalDateTime): List<DailyCount>
    @Query(
        nativeQuery = true,
        value = """
        select sh.food_id from scan_history sh
        join food f on f.id = sh.food_id
        where sh.member_id = :memberId
          and sh.status = 'ACTIVE'
          and f.status = 'ACTIVE'
          and f.content_status = 'READY'
        group by sh.food_id
        order by max(sh.created_at) desc
        limit :limit
        """,
    )
    fun findRecentReadyFoodIds(@Param("memberId") memberId: Long, @Param("limit") limit: Int): List<Long>

    @Query(
        nativeQuery = true,
        value = """
        select sh.food_id from scan_history sh
        join food f on f.id = sh.food_id
        where sh.member_id = :memberId
          and sh.status = 'ACTIVE'
          and f.status = 'ACTIVE'
          and f.content_status = 'READY'
          and (f.display_name collate utf8mb4_unicode_ci like concat('%', :kw, '%') escape '\\'
               or (:jsonPath is not null
                   and json_unquote(json_extract(f.name_translations, :jsonPath)) collate utf8mb4_unicode_ci
                     like concat('%', :kw, '%') escape '\\'))
        group by sh.food_id
        order by max(sh.created_at) desc, sh.food_id desc
        """,
    )
    fun findScannedFoodIds(
        @Param("memberId") memberId: Long,
        @Param("kw") keyword: String,
        @Param("jsonPath") jsonPath: String?,
    ): List<Long>

    @Query(
        nativeQuery = true,
        value = """
        select t.food_id from (
            select sh.food_id as food_id, max(sh.created_at) as last_scanned_at
            from scan_history sh
            join food f on f.id = sh.food_id
            where sh.member_id = :memberId
              and sh.status = 'ACTIVE'
              and f.status = 'ACTIVE'
              and f.content_status = 'READY'
            group by sh.food_id
        ) t
        where (:cursorLastScannedAt is null
               or t.last_scanned_at < :cursorLastScannedAt
               or (t.last_scanned_at = :cursorLastScannedAt and t.food_id < :cursorFoodId))
        order by t.last_scanned_at desc, t.food_id desc
        limit :size
        """,
    )
    fun findScannedFoodPageIds(
        @Param("memberId") memberId: Long,
        @Param("cursorLastScannedAt") cursorLastScannedAt: LocalDateTime?,
        @Param("cursorFoodId") cursorFoodId: Long?,
        @Param("size") size: Int,
    ): List<Long>

    @Query(
        nativeQuery = true,
        value = """
        select max(sh.created_at) from scan_history sh
        where sh.member_id = :memberId
          and sh.food_id = :foodId
          and sh.status = 'ACTIVE'
        """,
    )
    fun findLastScannedAt(@Param("memberId") memberId: Long, @Param("foodId") foodId: Long): LocalDateTime?
}
