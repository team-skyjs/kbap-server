package com.kbap.application.auth

import com.kbap.domain.member.MemberRole

data class ParsedAccessToken(
    val memberId: Long,
    val role: MemberRole,
) {
    val roleName: String get() = role.name
}
