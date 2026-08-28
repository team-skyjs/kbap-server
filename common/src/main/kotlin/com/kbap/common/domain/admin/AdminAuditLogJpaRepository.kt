package com.kbap.common.domain.admin

import com.kbap.common.domain.admin.model.AdminAuditLog
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface AdminAuditLogJpaRepository : JpaRepository<AdminAuditLog, Long>, AdminAuditLogQueryRepositoryCustom {
    fun findByTargetTypeAndTargetIdOrderByIdDesc(
        targetType: AdminAuditTargetType,
        targetId: Long,
        pageable: Pageable,
    ): List<AdminAuditLog>
}
