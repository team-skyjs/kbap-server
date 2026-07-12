package com.meogo.infra.persistence.member

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemberRankingJpaRepository : JpaRepository<MemberRankingJpaEntity, Long> {
    @Modifying
    @Query(
        value = """
        insert into member_ranking (member_id, scan_count, status, created_at, updated_at)
        values (:memberId, 1, 'ACTIVE', current_timestamp(6), current_timestamp(6))
        on duplicate key update scan_count = scan_count + 1, updated_at = current_timestamp(6)
        """,
        nativeQuery = true,
    )
    fun increaseScanCount(@Param("memberId") memberId: Long)

    @Query("select r.scanCount from MemberRankingJpaEntity r where r.memberId = :memberId")
    fun findScanCountByMemberId(@Param("memberId") memberId: Long): Int?
}
