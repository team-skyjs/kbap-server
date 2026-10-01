package com.kbap.common.domain.report

import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportHandleResult
import com.kbap.common.domain.report.model.ReportTargetType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ReportTargetSummary {
    val targetType: String
    val targetId: Long
    val totalCount: Number
    val pendingCount: Number
    val reporterCount: Number
    val latestId: Long
}

interface ReportJpaRepository : JpaRepository<Report, Long> {
    @Query(
        nativeQuery = true,
        value = """
            select r.target_type as targetType, r.target_id as targetId, count(*) as totalCount,
                   sum(r.handle_status = 'PENDING') as pendingCount,
                   count(distinct case when r.reporter_member_id is not null then concat('m:', r.reporter_member_id)
                                       else concat('g:', r.reporter_installation_id) end) as reporterCount,
                   max(r.id) as latestId
            from report r
            where r.status = 'ACTIVE' and (:targetType is null or r.target_type = :targetType)
            group by r.target_type, r.target_id
            having (sum(r.handle_status = 'PENDING') > 0) = :pending
            order by latestId desc
            limit :limit offset :offset
        """,
    )
    fun findTargetSummaries(
        @Param("targetType") targetType: String?,
        @Param("pending") pending: Boolean,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<ReportTargetSummary>

    @Query(
        nativeQuery = true,
        value = """
            select count(*) from (
                select 1 from report r
                where r.status = 'ACTIVE' and (:targetType is null or r.target_type = :targetType)
                group by r.target_type, r.target_id
                having (sum(r.handle_status = 'PENDING') > 0) = :pending
            ) g
        """,
    )
    fun countTargetSummaries(@Param("targetType") targetType: String?, @Param("pending") pending: Boolean): Long

    fun findByTargetTypeAndTargetIdInOrderByIdDesc(targetType: ReportTargetType, targetIds: Collection<Long>): List<Report>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        update Report r set r.handleStatus = com.kbap.common.domain.report.model.ReportHandleStatus.HANDLED,
            r.handleResult = :result, r.handledBy = :handledBy, r.handledAt = :handledAt, r.handleNote = :note
        where r.targetType = :targetType and r.targetId = :targetId
          and r.handleStatus = com.kbap.common.domain.report.model.ReportHandleStatus.PENDING
        """,
    )
    fun handlePendingOfTarget(
        @Param("targetType") targetType: ReportTargetType,
        @Param("targetId") targetId: Long,
        @Param("result") result: ReportHandleResult,
        @Param("handledBy") handledBy: Long,
        @Param("handledAt") handledAt: LocalDateTime,
        @Param("note") note: String?,
    ): Int
}
