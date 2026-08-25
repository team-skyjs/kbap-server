package com.kbap.common.domain.report

import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportTargetType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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
        join food_review fr on r.target_type = 'REVIEW' and r.target_id = fr.id
        where fr.member_id = :memberId
          and r.status = 'ACTIVE'
        """,
    )
    fun countReceivedByMemberId(@Param("memberId") memberId: Long): Long
}
