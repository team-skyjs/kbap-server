package com.kbap.api.notification

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/notifications")
class NotificationInboxController(
    private val notificationInboxService: NotificationInboxService,
) : NotificationInboxApi {
    @GetMapping
    override fun getRecentNotifications(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<List<NotificationResponse>>> =
        ResponseEntity.ok(BaseResponse.ok(notificationInboxService.getRecentNotifications(memberId)))

    @PatchMapping("/{notificationId}/read")
    override fun markRead(
        @AuthMemberId memberId: Long,
        @PathVariable notificationId: Long,
    ): ResponseEntity<BaseResponse<NotificationResponse>> =
        ResponseEntity.ok(BaseResponse.ok(notificationInboxService.markRead(memberId, notificationId)))
}
