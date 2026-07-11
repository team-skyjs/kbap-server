package com.meogo.infra.persistence.scan

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScanHistoryJpaRepository : JpaRepository<ScanHistoryJpaEntity, Long> {
    @Query(
        """
        select sh.foodId from ScanHistoryJpaEntity sh
        join FoodJpaEntity f on f.id = sh.foodId
        where sh.memberId = :memberId
          and f.contentStatus = 'READY'
        group by sh.foodId
        order by max(sh.createdAt) desc
        """,
    )
    fun findRecentReadyFoodIds(@Param("memberId") memberId: Long, pageable: Pageable): List<Long>
}
