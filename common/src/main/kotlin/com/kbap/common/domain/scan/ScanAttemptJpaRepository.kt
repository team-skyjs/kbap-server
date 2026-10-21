package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanAttempt
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ScanAttemptJpaRepository : JpaRepository<ScanAttempt, Long> {
    @Query(
        """
        select count(a) from ScanAttempt a
        where a.memberId = :memberId and a.startedAt >= :since
          and exists (select 1 from ScanOperation o where o.id = a.operationId and o.usageRecordedAt is null)
        """,
    )
    fun countUnchargedSince(@Param("memberId") memberId: Long, @Param("since") since: LocalDateTime): Long

    @Query(nativeQuery = true, value = "SELECT global_call_limit FROM scan_runtime_control WHERE singleton_key = 1 FOR UPDATE")
    fun lockGlobalCallLimit(): Int?

    @Query("select count(a) from ScanAttempt a where a.callSlotHeld = true and a.leaseUntil > :now")
    fun countLiveCallSlots(@Param("now") now: LocalDateTime): Long

    @Query("select count(a) from ScanAttempt a where a.memberId = :memberId and a.callSlotHeld = true and a.leaseUntil > :now")
    fun countLiveCallSlotsOfMember(@Param("memberId") memberId: Long, @Param("now") now: LocalDateTime): Long
}
