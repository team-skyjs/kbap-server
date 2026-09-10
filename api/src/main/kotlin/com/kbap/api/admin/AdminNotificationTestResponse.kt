package com.kbap.api.admin

import com.kbap.common.domain.notification.PushDispatchResult
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "관리자 테스트 푸시 발송 결과")
data class AdminNotificationTestResponse(
    @field:Schema(description = "Expo 가 ok 티켓을 준 기기 수", example = "1")
    val sent: Int,
    @field:Schema(description = "error 티켓·전송 실패 기기 수", example = "0")
    val failed: Int,
) {
    companion object {
        fun from(result: PushDispatchResult) = AdminNotificationTestResponse(sent = result.sent, failed = result.failed)
    }
}
