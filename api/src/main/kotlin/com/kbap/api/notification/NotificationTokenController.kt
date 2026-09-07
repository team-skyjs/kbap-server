package com.kbap.api.notification

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.notification.model.DevicePlatform
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/notifications")
class NotificationTokenController(
    private val notificationTokenService: NotificationTokenService,
) : NotificationTokenApi {
    @PutMapping("/tokens", version = "1.1+")
    override fun register(
        @RequestHeader(ApiHeaders.INSTALLATION_ID) installationId: String,
        @AuthMemberIdOrNull memberId: Long?,
        @Valid @RequestBody request: NotificationTokenRegisterRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        notificationTokenService.registerToken(
            installationId = validInstallationId(installationId),
            memberId = memberId,
            token = request.token!!,
            platform = DevicePlatform.valueOf(request.platform!!.uppercase()),
            lang = request.lang!!,
        )
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    private fun validInstallationId(raw: String): String {
        if (raw.isBlank() || raw.length > INSTALLATION_ID_MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        return raw
    }

    companion object {
        private const val INSTALLATION_ID_MAX_LENGTH = 36
    }
}
