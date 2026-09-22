package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/notifications", version = "1.0+")
class AdminNotificationTestController(
    private val adminNotificationTestService: AdminNotificationTestService,
) : AdminNotificationTestApi {
    @PostMapping("/test-push")
    override fun sendTestPush(
        @RequestBody @Valid request: AdminNotificationTestRequest,
    ): ResponseEntity<BaseResponse<AdminNotificationTestResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(AdminNotificationTestResponse.from(adminNotificationTestService.sendTestPush(request.memberId!!))),
        )
}
