package com.kbap.api.admin

import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.MemberStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminDashboardMetricsService(
    private val memberRepository: MemberJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getMetrics(): AdminDashboardMetricsView =
        AdminDashboardMetricsView(
            totalActiveMembers = memberRepository.countByMemberStatus(MemberStatus.ACTIVE),
        )
}

data class AdminDashboardMetricsView(
    val totalActiveMembers: Long,
)
