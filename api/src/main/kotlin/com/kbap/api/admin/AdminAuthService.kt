package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.LoginAttemptStore
import com.kbap.common.port.auth.RefreshTokenStore
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.auth.TokenParser
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

data class AdminTokens(
    val adminId: Long,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

@Service
class AdminAuthService(
    private val adminAccountRepository: AdminAccountJpaRepository,
    private val tokenIssuer: TokenIssuer,
    private val tokenParser: TokenParser,
    private val refreshTokenStore: RefreshTokenStore,
    private val loginAttemptStore: LoginAttemptStore,
    private val auditRecorder: AdminAuditRecorder,
    @Value("\${kbap.auth.admin.access-ttl}") private val accessTtl: Duration,
    @Value("\${kbap.auth.admin.refresh-ttl}") private val refreshTtl: Duration,
) {
    private val passwordEncoder = BCryptPasswordEncoder()

    @Transactional
    fun login(loginId: String, password: String): AdminTokens {
        if (loginAttemptStore.isLocked(loginId)) throw BusinessException(ErrorCode.ADMIN_LOGIN_LOCKED)
        val account = adminAccountRepository.findByLoginId(loginId)
        if (account == null || !passwordEncoder.matches(password, account.password)) {
            loginAttemptStore.recordFailure(loginId)
            throw BusinessException(ErrorCode.ADMIN_LOGIN_FAILED)
        }
        loginAttemptStore.reset(loginId)
        auditRecorder.record(account.id, AdminAuditAction.ADMIN_LOGIN, AdminAuditTargetType.ADMIN_ACCOUNT, account.id, null, null)
        return issue(account.id)
    }

    fun refresh(refreshToken: String): AdminTokens {
        val parsed = try {
            tokenParser.parseRefreshToken(refreshToken)
        } catch (e: BusinessException) {
            if (e.errorCode == ErrorCode.EXPIRED_REFRESH_TOKEN) {
                tokenParser.refreshTokenJtiOrNull(refreshToken)?.let { refreshTokenStore.delete(it) }
            }
            throw e
        }
        if (parsed.role != MemberRole.ADMIN) throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        val adminId = refreshTokenStore.consume(parsed.jti) ?: throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        if (!adminAccountRepository.existsById(adminId)) throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        return issue(adminId)
    }

    fun logout(refreshToken: String?) {
        if (refreshToken.isNullOrBlank()) return
        tokenParser.refreshTokenJtiOrNull(refreshToken)?.let { refreshTokenStore.delete(it) }
    }

    private fun issue(adminId: Long): AdminTokens {
        val refresh = tokenIssuer.issueRefreshToken(adminId, MemberRole.ADMIN)
        refreshTokenStore.save(refresh.jti, adminId, refreshTtl)
        return AdminTokens(
            adminId = adminId,
            accessToken = tokenIssuer.issueAccessToken(adminId, MemberRole.ADMIN),
            refreshToken = refresh.token,
            expiresIn = accessTtl.seconds,
        )
    }
}
