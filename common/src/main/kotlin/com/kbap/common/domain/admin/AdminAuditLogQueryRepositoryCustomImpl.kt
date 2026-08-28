package com.kbap.common.domain.admin

import com.kbap.common.domain.admin.model.AdminAuditLog
import jakarta.persistence.EntityManager

class AdminAuditLogQueryRepositoryCustomImpl(
    private val entityManager: EntityManager,
) : AdminAuditLogQueryRepositoryCustom {
    override fun findPage(filter: AdminAuditLogFilter, page: Int, size: Int): AdminAuditLogPageRows {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        filter.targetType?.let { conditions += "l.targetType = :targetType"; params["targetType"] = it }
        filter.targetId?.let { conditions += "l.targetId = :targetId"; params["targetId"] = it }
        filter.adminAccountId?.let { conditions += "l.adminAccountId = :adminAccountId"; params["adminAccountId"] = it }
        filter.action?.let { conditions += "l.action = :action"; params["action"] = it }
        filter.from?.let { conditions += "l.createdAt >= :from"; params["from"] = it }
        filter.to?.let { conditions += "l.createdAt < :to"; params["to"] = it }
        val where = if (conditions.isEmpty()) "" else " where " + conditions.joinToString(" and ")

        val rows = entityManager.createQuery("select l from AdminAuditLog l$where order by l.id desc", AdminAuditLog::class.java)
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .setFirstResult((page - 1) * size)
            .setMaxResults(size)
            .resultList
        val total = entityManager.createQuery("select count(l) from AdminAuditLog l$where", java.lang.Long::class.java)
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .singleResult
        return AdminAuditLogPageRows(rows = rows, totalCount = total.toLong())
    }
}
