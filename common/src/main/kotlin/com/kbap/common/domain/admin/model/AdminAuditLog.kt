package com.kbap.common.domain.admin.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(
    name = "admin_audit_log",
    indexes = [
        Index(name = "idx_admin_audit_target", columnList = "target_type, target_id, id"),
        Index(name = "idx_admin_audit_admin", columnList = "admin_account_id, id"),
        Index(name = "idx_admin_audit_action", columnList = "action, id"),
    ],
)
class AdminAuditLog(
    @Column(name = "admin_account_id", nullable = false)
    var adminAccountId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 50)
    var action: AdminAuditAction = AdminAuditAction.FOOD_UPDATE,

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    var targetType: AdminAuditTargetType = AdminAuditTargetType.FOOD,

    @Column(name = "target_id")
    var targetId: Long? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json")
    var beforeJson: Map<String, Any?>? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json")
    var afterJson: Map<String, Any?>? = null,

    @Column(name = "note", length = MAX_NOTE_LENGTH)
    var note: String? = null,
) : BaseEntity() {
    companion object {
        const val MAX_NOTE_LENGTH = 500
    }
}
