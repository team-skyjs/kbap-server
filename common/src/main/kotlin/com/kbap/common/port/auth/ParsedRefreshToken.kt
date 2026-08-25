package com.kbap.common.port.auth

import com.kbap.common.domain.member.model.MemberRole

data class ParsedRefreshToken(
    val memberId: Long,
    val jti: String,
    val role: MemberRole = MemberRole.USER,
)
