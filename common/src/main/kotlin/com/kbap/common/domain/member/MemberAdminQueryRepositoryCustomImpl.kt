package com.kbap.common.domain.member

import com.kbap.common.domain.member.dto.AdminMemberRow
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import jakarta.persistence.EntityManager
import java.sql.Timestamp
import java.time.LocalDateTime

class MemberAdminQueryRepositoryCustomImpl(
    private val entityManager: EntityManager,
) : MemberAdminQueryRepositoryCustom {
    override fun findAdminPage(filter: AdminMemberFilter, page: Int, size: Int): AdminMemberRows {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (!filter.includeWithdrawn) conditions += "m.status = 'ACTIVE'"
        filter.q?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            q.toLongOrNull()?.let { conditions += "m.id = :id"; params["id"] = it }
                ?: run { conditions += "m.nickname like :nickname"; params["nickname"] = "%$q%" }
        }
        filter.email?.trim()?.takeIf { it.isNotEmpty() }?.let { conditions += "m.email like :email"; params["email"] = "%$it%" }
        filter.provider?.let { conditions += "m.provider = :provider"; params["provider"] = it.name }
        filter.memberStatus?.let { conditions += "m.member_status = :memberStatus"; params["memberStatus"] = it.name }
        filter.onboardingCompleted?.let { conditions += "m.onboarding_completed = :onboarding"; params["onboarding"] = it }
        filter.createdFrom?.let { conditions += "m.created_at >= :createdFrom"; params["createdFrom"] = it.atStartOfDay() }
        filter.createdTo?.let { conditions += "m.created_at < :createdTo"; params["createdTo"] = it.plusDays(1).atStartOfDay() }
        val where = if (conditions.isEmpty()) "" else " where " + conditions.joinToString(" and ")
        val order = " order by ${filter.sort.column} ${if (filter.descending) "desc" else "asc"}, m.id desc"

        val rows = entityManager.createNativeQuery(
            "select m.id, m.nickname, m.email, m.provider, m.member_status, m.onboarding_completed, m.status, m.created_at, m.updated_at " +
                "from member m$where$order",
        )
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .setFirstResult((page - 1) * size)
            .setMaxResults(size)
            .resultList
            .map { toRow(it as Array<*>) }
        val total = entityManager.createNativeQuery("select count(*) from member m$where")
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .singleResult as Number
        return AdminMemberRows(rows = rows, totalCount = total.toLong())
    }

    private fun toRow(r: Array<*>): AdminMemberRow =
        AdminMemberRow(
            id = (r[0] as Number).toLong(),
            nickname = r[1] as String?,
            email = r[2] as String?,
            provider = SocialProvider.valueOf(r[3] as String),
            memberStatus = MemberStatus.valueOf(r[4] as String),
            onboardingCompleted = toBoolean(r[5]),
            withdrawn = r[6] != "ACTIVE",
            createdAt = toLocalDateTime(r[7]),
            updatedAt = toLocalDateTime(r[8]),
        )

    private fun toBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }

    private fun toLocalDateTime(value: Any?): LocalDateTime = when (value) {
        is LocalDateTime -> value
        is Timestamp -> value.toLocalDateTime()
        else -> error("datetime 을 해석할 수 없습니다: $value")
    }
}
