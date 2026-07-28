package com.kbap.common.application.auth.dto

import com.kbap.common.domain.member.model.MemberRole

data class ParsedAccessToken(
    val memberId: Long,
    val role: MemberRole,
) {
    val roleName: String get() = role.name
}
