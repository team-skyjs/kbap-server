package com.kbap.application.auth.token

import com.kbap.application.auth.dto.IssuedRefreshToken
import com.kbap.domain.member.MemberRole

interface TokenIssuer {
    fun issueAccessToken(memberId: Long, role: MemberRole): String

    fun issueRefreshToken(memberId: Long): IssuedRefreshToken
}
