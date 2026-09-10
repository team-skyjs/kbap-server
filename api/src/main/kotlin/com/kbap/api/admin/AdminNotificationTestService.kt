package com.kbap.api.admin

import com.kbap.api.member.MemberService
import com.kbap.api.notification.PushNotificationService
import com.kbap.common.domain.notification.PushDispatchResult
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.stereotype.Service

@Service
class AdminNotificationTestService(
    private val memberService: MemberService,
    private val pushNotificationService: PushNotificationService,
) {
    fun sendTestPush(memberId: Long): PushDispatchResult {
        memberService.getMember(memberId)
        return pushNotificationService.send(
            PushRequest(NotificationType.NOTICE, listOf(memberId), args = mapOf("title" to TEST_TITLE, "body" to TEST_BODY)),
        )
    }

    companion object {
        private const val TEST_TITLE = "K-Bap"
        private const val TEST_BODY = "테스트 알림입니다."
    }
}
