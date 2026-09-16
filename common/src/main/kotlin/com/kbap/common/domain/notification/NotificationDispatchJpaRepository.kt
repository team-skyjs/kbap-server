package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationDispatch
import com.kbap.common.domain.notification.model.NotificationDispatchStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface NotificationDispatchJpaRepository : JpaRepository<NotificationDispatch, Long> {
    fun findByNotificationId(notificationId: Long): List<NotificationDispatch>

    fun findByDispatchStatusAndCreatedAtBefore(
        status: NotificationDispatchStatus,
        before: LocalDateTime,
        pageable: Pageable,
    ): List<NotificationDispatch>
}
