package com.kbap.api.notification

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/notifications")
class NotificationSettingController(
    private val notificationSettingService: NotificationSettingService,
) : NotificationSettingApi {
    @GetMapping("/settings", version = "1.1+")
    override fun getSettings(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<NotificationSettingsResponse>> {
        val result = notificationSettingService.getSettings(memberId)
        return ResponseEntity.ok(BaseResponse.ok(NotificationSettingsResponse.from(result)))
    }

    @PatchMapping("/settings", version = "1.1+")
    override fun updateSettings(
        @AuthMemberId memberId: Long,
        @RequestHeader(ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @Valid @RequestBody request: NotificationSettingsUpdateRequest,
    ): ResponseEntity<BaseResponse<NotificationSettingsResponse>> {
        val result = notificationSettingService.updateSettings(memberId, installationId, request)
        return ResponseEntity.ok(BaseResponse.ok(NotificationSettingsResponse.from(result)))
    }
}
