package com.kbap.api.notification

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.notification.NotificationJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class NotificationInboxService(
    private val notificationRepository: NotificationJpaRepository,
    private val memberService: MemberService,
) {
    @Transactional(readOnly = true)
    fun getRecentNotifications(memberId: Long): List<NotificationResponse> {
        memberService.getMember(memberId)
        val since = LocalDateTime.now().minusDays(RECENT_DAYS)
        return notificationRepository.findByMemberIdAndCreatedAtAfterOrderByIdDesc(memberId, since)
            .map(NotificationResponse::from)
    }

    @Transactional
    fun markRead(memberId: Long, notificationId: Long): NotificationResponse {
        memberService.getMember(memberId)
        val notification = notificationRepository.findByIdAndMemberId(notificationId, memberId)
            ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        notification.markRead(LocalDateTime.now())
        return NotificationResponse.from(notification)
    }

    companion object {
        const val RECENT_DAYS = 7L
    }
}
