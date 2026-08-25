package com.kbap.api.infra.auth.token

import com.kbap.common.domain.member.model.MemberRole
import java.time.Duration

data class JwtTokenProperties(
    val secret: String,
    val accessTtl: Duration,
    val refreshTtl: Duration,
    val adminAccessTtl: Duration = accessTtl,
    val adminRefreshTtl: Duration = refreshTtl,
) {
    init {
        require(secret.toByteArray().size >= MIN_SECRET_BYTES) {
            "kbap.auth.jwt.secret 는 ${MIN_SECRET_BYTES}바이트 이상이어야 합니다"
        }
    }

    fun accessTtl(role: MemberRole): Duration = if (role == MemberRole.ADMIN) adminAccessTtl else accessTtl

    fun refreshTtl(role: MemberRole): Duration = if (role == MemberRole.ADMIN) adminRefreshTtl else refreshTtl

    companion object {
        private const val MIN_SECRET_BYTES = 32
    }
}
