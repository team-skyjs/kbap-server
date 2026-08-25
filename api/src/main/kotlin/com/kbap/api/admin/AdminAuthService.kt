package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

data class AdminTokens(
    val adminId: Long,
    val accessToken: String,
    val expiresIn: Long,
)

@Service
class AdminAuthService(
    private val adminAccountRepository: AdminAccountJpaRepository,
    private val tokenIssuer: TokenIssuer,
    private val auditRecorder: AdminAuditRecorder,
    @Value("\${kbap.auth.admin.access-ttl}") private val accessTtl: Duration,
) {
    private val passwordEncoder = BCryptPasswordEncoder()

    @Transactional
    fun login(loginId: String, password: String): AdminTokens {
        val account = adminAccountRepository.findByLoginId(loginId)
        if (account == null || !passwordEncoder.matches(password, account.password)) {
            throw BusinessException(ErrorCode.ADMIN_LOGIN_FAILED)
        }
        account.recordLogin()
        auditRecorder.record(account.id, AdminAuditAction.ADMIN_LOGIN, AdminAuditTargetType.ADMIN_ACCOUNT, account.id, null, null)
        return AdminTokens(
            adminId = account.id,
            accessToken = tokenIssuer.issueAccessToken(account.id, MemberRole.ADMIN),
            expiresIn = accessTtl.seconds,
        )
    }
}
