package com.kbap.common.domain.admin

import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditLog
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import java.time.LocalDateTime

data class AdminAuditLogFilter(
    val targetType: AdminAuditTargetType? = null,
    val targetId: Long? = null,
    val adminAccountId: Long? = null,
    val action: AdminAuditAction? = null,
    val from: LocalDateTime? = null,
    val to: LocalDateTime? = null,
)

data class AdminAuditLogPageRows(
    val rows: List<AdminAuditLog>,
    val totalCount: Long,
)

interface AdminAuditLogQueryRepositoryCustom {
    fun findPage(filter: AdminAuditLogFilter, page: Int, size: Int): AdminAuditLogPageRows
}
