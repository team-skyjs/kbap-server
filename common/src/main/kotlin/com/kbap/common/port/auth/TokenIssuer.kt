package com.kbap.common.port.auth

import com.kbap.common.domain.member.model.MemberRole

interface TokenIssuer {
    fun issueAccessToken(memberId: Long, role: MemberRole): String

    fun issueRefreshToken(memberId: Long, role: MemberRole = MemberRole.USER): IssuedRefreshToken
}
