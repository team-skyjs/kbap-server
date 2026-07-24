package com.kbap.application.auth.dto

import com.kbap.domain.member.model.MemberRole

data class ParsedAccessToken(
    val memberId: Long,
    val role: MemberRole,
) {
    val roleName: String get() = role.name
}
