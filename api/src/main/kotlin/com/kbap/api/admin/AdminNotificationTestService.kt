package com.kbap.api.admin

import com.kbap.api.member.MemberService
import com.kbap.common.domain.notification.PushDispatchResult
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.port.push.PushHandler
import org.springframework.stereotype.Service

@Service
class AdminNotificationTestService(
    private val memberService: MemberService,
    private val pushHandler: PushHandler,
) {
    fun sendTestPush(memberId: Long): PushDispatchResult {
        memberService.getMember(memberId)
        return pushHandler.send(
            PushRequest(NotificationType.NEWS, listOf(memberId), args = mapOf("title" to TEST_TITLE, "body" to TEST_BODY)),
        )
    }

    companion object {
        private const val TEST_TITLE = "K-Bap"
        private const val TEST_BODY = "테스트 알림입니다."
    }
}
