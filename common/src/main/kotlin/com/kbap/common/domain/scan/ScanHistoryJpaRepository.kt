package com.kbap.common.domain.scan

import com.kbap.common.domain.DailyCount
import com.kbap.common.domain.scan.model.ScanHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ScanHistoryJpaRepository : JpaRepository<ScanHistory, Long> {
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
}
