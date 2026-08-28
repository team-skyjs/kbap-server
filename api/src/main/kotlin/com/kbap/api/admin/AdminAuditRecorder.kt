package com.kbap.api.admin

import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditLog
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class AdminAuditRecorder(
    private val repository: AdminAuditLogJpaRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        adminId: Long,
        action: AdminAuditAction,
        targetType: AdminAuditTargetType,
        targetId: Long?,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
        note: String? = null,
    ): AdminAuditLog {
        val (changedBefore, changedAfter) = changedOnly(before, after)
        return repository.save(
            AdminAuditLog(
                adminAccountId = adminId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                beforeJson = changedBefore,
                afterJson = changedAfter,
                note = note?.take(AdminAuditLog.MAX_NOTE_LENGTH),
            ),
        )
    }

    private fun changedOnly(
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
    ): Pair<Map<String, Any?>?, Map<String, Any?>?> {
        if (before == null || after == null) return before to after
        val changedKeys = (before.keys + after.keys).filter { before[it] != after[it] }
        return before.filterKeys { it in changedKeys } to after.filterKeys { it in changedKeys }
    }
}
