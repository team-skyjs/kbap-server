package com.kbap.common.domain.report

import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportHandleStatus
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReportTargetCount {
    val targetId: Long
    val reportCount: Long
}

interface ReportJpaRepository : JpaRepository<Report, Long> {
    fun existsByReporterMemberIdAndTargetTypeAndTargetId(
        reporterMemberId: Long,
        targetType: ReportTargetType,
        targetId: Long,
    ): Boolean

    @Query(
        """
        select r.targetId from Report r
        where r.reporterMemberId = :reporterMemberId
          and r.targetType = :targetType
        """,
    )
    fun findTargetIdsByReporterMemberIdAndTargetType(
        @Param("reporterMemberId") reporterMemberId: Long,
        @Param("targetType") targetType: ReportTargetType,
    ): List<Long>

    fun countByReporterMemberId(reporterMemberId: Long): Long

    @Query(
        nativeQuery = true,
        value = """
        select count(*) from report r
        left join food_review fr on r.target_type = 'REVIEW' and r.target_id = fr.id
        left join community_post cp on r.target_type = 'POST' and r.target_id = cp.id
        left join community_comment cc on r.target_type = 'COMMENT' and r.target_id = cc.id
        where r.status = 'ACTIVE'
          and coalesce(fr.member_id, cp.member_id, cc.member_id) = :memberId
        """,
    )
    fun countReceivedByMemberId(@Param("memberId") memberId: Long): Long

    @Query(
        """
        select r from Report r
        where (:handleStatus is null or r.handleStatus = :handleStatus)
          and (:reason is null or r.reason = :reason)
          and (:targetType is null or r.targetType = :targetType)
        order by r.id desc
        """,
    )
    fun findAdminPage(
        @Param("handleStatus") handleStatus: ReportHandleStatus?,
        @Param("reason") reason: ReportReason?,
        @Param("targetType") targetType: ReportTargetType?,
        pageable: Pageable,
    ): Page<Report>

    @Query(
        """
        select r.targetId as targetId, count(r) as reportCount from Report r
        where r.targetType = :targetType and r.targetId in :targetIds
        group by r.targetId
        """,
    )
    fun countByTarget(
        @Param("targetType") targetType: ReportTargetType,
        @Param("targetIds") targetIds: Collection<Long>,
    ): List<ReportTargetCount>

    fun findByTargetTypeAndTargetIdAndHandleStatus(
        targetType: ReportTargetType,
        targetId: Long,
        handleStatus: ReportHandleStatus,
    ): List<Report>
}
