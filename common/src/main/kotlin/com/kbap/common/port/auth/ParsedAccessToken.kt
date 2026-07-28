package com.kbap.common.port.auth

import com.kbap.common.domain.member.model.MemberRole

data class ParsedAccessToken(
    val memberId: Long,
    val role: MemberRole,
) {
    val roleName: String get() = role.name
}
