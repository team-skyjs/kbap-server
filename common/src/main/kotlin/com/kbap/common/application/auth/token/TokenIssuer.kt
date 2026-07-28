package com.kbap.common.application.auth.token

import com.kbap.common.application.auth.dto.IssuedRefreshToken
import com.kbap.common.domain.member.model.MemberRole

interface TokenIssuer {
    fun issueAccessToken(memberId: Long, role: MemberRole): String

    fun issueRefreshToken(memberId: Long): IssuedRefreshToken
}
