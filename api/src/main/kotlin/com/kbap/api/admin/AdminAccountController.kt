package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

data class AdminAccountCreateRequest(
    @field:NotBlank(message = "loginId 는 필수입니다")
    @field:Size(min = AdminAccount.MIN_LOGIN_ID_LENGTH, max = AdminAccount.MAX_LOGIN_ID_LENGTH, message = "loginId 는 4~50자입니다")
    @field:Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "loginId 는 영문·숫자·._- 만 허용합니다")
    @field:Schema(description = "로그인 아이디", example = "ops2")
    val loginId: String?,
    @field:NotBlank(message = "password 는 필수입니다")
    @field:Size(min = AdminAccount.MIN_PASSWORD_LENGTH, message = "password 는 8자 이상입니다")
    @field:Schema(description = "초기 비밀번호(8자 이상)")
    val password: String?,
)

data class AdminPasswordChangeRequest(
    @field:NotBlank(message = "currentPassword 는 필수입니다")
    val currentPassword: String?,
    @field:NotBlank(message = "newPassword 는 필수입니다")
    @field:Size(min = AdminAccount.MIN_PASSWORD_LENGTH, message = "newPassword 는 8자 이상입니다")
    val newPassword: String?,
)

data class AdminAccountResponse(
    val id: Long,
    val loginId: String,
    val lastLoginAt: LocalDateTime?,
    val passwordChangedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(account: AdminAccount): AdminAccountResponse =
            AdminAccountResponse(account.id, account.loginId, account.lastLoginAt, account.passwordChangedAt, account.createdAt)
    }
}

data class AdminAccountListResponse(val items: List<AdminAccountResponse>)

data class AdminAccountDeleteResponse(val id: Long, val deleted: Boolean)

@Service
class AdminAccountService(
    private val adminAccountRepository: AdminAccountJpaRepository,
    private val auditRecorder: AdminAuditRecorder,
) {
    private val passwordEncoder = BCryptPasswordEncoder()

    @Transactional(readOnly = true)
    fun getAccounts(): AdminAccountListResponse =
        AdminAccountListResponse(adminAccountRepository.findAll().sortedBy { it.id }.map(AdminAccountResponse::from))

    @Transactional
    fun createAccount(adminId: Long, loginId: String, rawPassword: String): AdminAccountResponse {
        if (adminAccountRepository.findByLoginId(loginId) != null) throw BusinessException(ErrorCode.ADMIN_ACCOUNT_DUPLICATED)
        val account = try {
            adminAccountRepository.saveAndFlush(AdminAccount(loginId = loginId, password = passwordEncoder.encode(rawPassword)!!))
        } catch (_: DataIntegrityViolationException) {
            throw BusinessException(ErrorCode.ADMIN_ACCOUNT_DUPLICATED)
        }
        auditRecorder.record(adminId, AdminAuditAction.ADMIN_ACCOUNT_CREATE, AdminAuditTargetType.ADMIN_ACCOUNT, account.id, null, mapOf("loginId" to loginId))
        return AdminAccountResponse.from(account)
    }

    @Transactional
    fun changeMyPassword(adminId: Long, currentPassword: String, newPassword: String): AdminAccountResponse {
        val account = getAccount(adminId)
        if (!passwordEncoder.matches(currentPassword, account.password)) throw BusinessException(ErrorCode.ADMIN_PASSWORD_MISMATCH)
        account.changePassword(passwordEncoder.encode(newPassword)!!)
        auditRecorder.record(adminId, AdminAuditAction.ADMIN_PASSWORD_CHANGE, AdminAuditTargetType.ADMIN_ACCOUNT, account.id, null, null)
        return AdminAccountResponse.from(account)
    }

    @Transactional
    fun deleteAccount(adminId: Long, targetId: Long): AdminAccountDeleteResponse {
        if (adminId == targetId) throw BusinessException(ErrorCode.ADMIN_SELF_ACTION_FORBIDDEN)
        val account = getAccount(targetId)
        account.delete()
        auditRecorder.record(adminId, AdminAuditAction.ADMIN_ACCOUNT_DELETE, AdminAuditTargetType.ADMIN_ACCOUNT, account.id, mapOf("loginId" to account.loginId), null)
        return AdminAccountDeleteResponse(id = account.id, deleted = true)
    }

    private fun getAccount(id: Long): AdminAccount =
        adminAccountRepository.findById(id).orElseThrow { BusinessException(ErrorCode.ADMIN_ACCOUNT_NOT_FOUND) }
}

@Tag(name = "관리자 계정", description = "관리자 계정 목록·생성·내 비밀번호 변경·삭제. 로그인 5회 실패 잠금은 로그인 API 가 담당한다")
@SecurityRequirement(name = "bearerAuth")
interface AdminAccountApi {
    @Operation(summary = "관리자 계정 목록", description = "id 순. 마지막 로그인·비밀번호 변경 시각 포함.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공")])
    fun getAccounts(): ResponseEntity<BaseResponse<AdminAccountListResponse>>

    @Operation(summary = "관리자 계정 생성", description = "아이디 4~50자(영문·숫자·._-), 비밀번호 8자 이상. 중복이면 409(AUTH-011).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "생성"), ApiResponse(responseCode = "400", description = "형식 오류(COMMON-002)"), ApiResponse(responseCode = "409", description = "아이디 중복(AUTH-011)")])
    fun createAccount(request: AdminAccountCreateRequest, adminId: Long): ResponseEntity<BaseResponse<AdminAccountResponse>>

    @Operation(summary = "내 비밀번호 변경", description = "현재 비밀번호가 다르면 400(AUTH-012).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "변경"), ApiResponse(responseCode = "400", description = "현재 비밀번호 불일치(AUTH-012)")])
    fun changeMyPassword(request: AdminPasswordChangeRequest, adminId: Long): ResponseEntity<BaseResponse<AdminAccountResponse>>

    @Operation(summary = "관리자 계정 삭제(소프트)", description = "본인은 삭제할 수 없다(400 AUTH-013). 삭제된 계정은 리프레시가 거부되고 액세스 토큰은 만료(1h)까지 유효하다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "삭제"), ApiResponse(responseCode = "400", description = "본인 삭제(AUTH-013)"), ApiResponse(responseCode = "404", description = "없는 계정(AUTH-014)")])
    fun deleteAccount(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminAccountDeleteResponse>>
}

@RestController
@RequestMapping(ApiPaths.ADMIN + "/accounts", version = "1.0+")
class AdminAccountController(
    private val adminAccountService: AdminAccountService,
) : AdminAccountApi {
    @GetMapping
    override fun getAccounts(): ResponseEntity<BaseResponse<AdminAccountListResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminAccountService.getAccounts()))

    @PostMapping
    override fun createAccount(
        @Valid @RequestBody request: AdminAccountCreateRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminAccountResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminAccountService.createAccount(adminId, request.loginId!!.trim(), request.password!!)))

    @PatchMapping("/me/password")
    override fun changeMyPassword(
        @Valid @RequestBody request: AdminPasswordChangeRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminAccountResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminAccountService.changeMyPassword(adminId, request.currentPassword!!, request.newPassword!!)))

    @DeleteMapping("/{id}")
    override fun deleteAccount(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminAccountDeleteResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminAccountService.deleteAccount(adminId, id)))
}
