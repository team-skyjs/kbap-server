package com.kbap.api.notification

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/notifications")
class NotificationController(
    private val notificationService: NotificationService,
) : NotificationApi {
    @GetMapping("/settings")
    override fun getSettings(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<NotificationSettingsResponse>> {
        val result = notificationService.getSettings(memberId)
        return ResponseEntity.ok(BaseResponse.ok(NotificationSettingsResponse.from(result)))
    }

    @PatchMapping("/settings")
    override fun updateSettings(
        @AuthMemberId memberId: Long,
        @RequestHeader(ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @Valid @RequestBody request: NotificationSettingsUpdateRequest,
    ): ResponseEntity<BaseResponse<NotificationSettingsResponse>> {
        val result = notificationService.updateSettings(memberId, installationId?.let(ApiHeaders::validInstallationId), request)
        return ResponseEntity.ok(BaseResponse.ok(NotificationSettingsResponse.from(result)))
    }

    @GetMapping
    override fun getRecentNotifications(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<List<NotificationResponse>>> =
        ResponseEntity.ok(BaseResponse.ok(notificationService.getRecentNotifications(memberId)))

    @PatchMapping("/{notificationId}/read")
    override fun markRead(
        @AuthMemberId memberId: Long,
        @PathVariable notificationId: Long,
    ): ResponseEntity<BaseResponse<NotificationResponse>> =
        ResponseEntity.ok(BaseResponse.ok(notificationService.markRead(memberId, notificationId)))
}
