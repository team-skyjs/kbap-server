package com.kbap.api.admin

import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.AdminAuditLogFilter
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditLog
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class AdminAuditLogResponse(
    val id: Long,
    val adminAccountId: Long,
    val adminLoginId: String?,
    val action: String,
    val targetType: String,
    val targetId: Long?,
    val before: Map<String, Any?>?,
    val after: Map<String, Any?>?,
    val note: String?,
    val createdAt: LocalDateTime,
)

data class AdminAuditLogPageResponse(
    val items: List<AdminAuditLogResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

@Service
class AdminAuditQueryService(
    private val auditLogRepository: AdminAuditLogJpaRepository,
    private val adminAccountRepository: AdminAccountJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getAuditLogPage(filter: AdminAuditLogFilter, page: Int, size: Int): AdminAuditLogPageResponse {
        val rows = auditLogRepository.findPage(filter, page, size)
        val totalPages = if (rows.totalCount == 0L) 0 else ((rows.totalCount - 1) / size + 1).toInt()
        return AdminAuditLogPageResponse(
            items = toResponses(rows.rows),
            page = page,
            size = size,
            totalCount = rows.totalCount,
            totalPages = totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getRecentLogsForTarget(targetType: AdminAuditTargetType, targetId: Long, limit: Int): List<AdminAuditLogResponse> =
        toResponses(auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc(targetType, targetId, PageRequest.of(0, limit)))

    private fun toResponses(logs: List<AdminAuditLog>): List<AdminAuditLogResponse> {
        val loginIds = adminAccountRepository.findAllById(logs.map { it.adminAccountId }.toSet())
            .associate { it.id to it.loginId }
        return logs.map {
            AdminAuditLogResponse(
                id = it.id,
                adminAccountId = it.adminAccountId,
                adminLoginId = loginIds[it.adminAccountId],
                action = it.action.name,
                targetType = it.targetType.name,
                targetId = it.targetId,
                before = it.beforeJson,
                after = it.afterJson,
                note = it.note,
                createdAt = it.createdAt,
            )
        }
    }
}
