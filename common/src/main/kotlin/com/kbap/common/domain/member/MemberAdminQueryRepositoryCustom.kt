package com.kbap.common.domain.member

import com.kbap.common.domain.member.dto.AdminMemberRow
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import java.time.LocalDate

enum class AdminMemberSort(val column: String) {
    ID("m.id"),
    CREATED_AT("m.created_at"),
    NICKNAME("m.nickname"),
}

data class AdminMemberFilter(
    val q: String? = null,
    val email: String? = null,
    val provider: SocialProvider? = null,
    val memberStatus: MemberStatus? = null,
    val onboardingCompleted: Boolean? = null,
    val createdFrom: LocalDate? = null,
    val createdTo: LocalDate? = null,
    val includeWithdrawn: Boolean = false,
    val sort: AdminMemberSort = AdminMemberSort.ID,
    val descending: Boolean = true,
)

data class AdminMemberRows(
    val rows: List<AdminMemberRow>,
    val totalCount: Long,
)

interface MemberAdminQueryRepositoryCustom {
    fun findAdminPage(filter: AdminMemberFilter, page: Int, size: Int): AdminMemberRows
}
