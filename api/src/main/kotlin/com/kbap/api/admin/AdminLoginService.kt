package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminLoginService(
    private val adminAccountJpaRepository: AdminAccountJpaRepository,
    private val tokenIssuer: TokenIssuer,
) {
    private val passwordEncoder = BCryptPasswordEncoder()

    @Transactional(readOnly = true)
    fun login(id: String, password: String): String? {
        val account = adminAccountJpaRepository.findByLoginId(id) ?: return null
        if (!passwordEncoder.matches(password, account.password)) return null
        return tokenIssuer.issueAccessToken(account.id, MemberRole.ADMIN)
    }

    @Transactional(readOnly = true)
    fun getMe(adminAccountId: Long): AdminMeResponse {
        val account = adminAccountJpaRepository.findById(adminAccountId).orElse(null)
            ?: throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        return AdminMeResponse(id = account.id, loginId = account.loginId, displayName = account.displayNameOrLoginId())
    }
}
