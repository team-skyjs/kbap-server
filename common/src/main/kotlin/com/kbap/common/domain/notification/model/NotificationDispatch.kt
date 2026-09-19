package com.kbap.common.domain.notification.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

@Entity
@Table(name = "notification_dispatch")
class NotificationDispatch(
    @Column(name = "notification_id", nullable = false)
    var notificationId: Long = 0,

    @Column(name = "notification_device_id")
    var notificationDeviceId: Long? = null,

    @Column(name = "expo_token", nullable = false, length = 255)
    var expoToken: String = "",

    @Column(name = "ticket_id", length = 64)
    var ticketId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(
        name = "dispatch_status",
        nullable = false,
        columnDefinition = "ENUM('PENDING','SENT','DELIVERED','FAILED') default 'PENDING'",
    )
    var dispatchStatus: NotificationDispatchStatus = NotificationDispatchStatus.PENDING,

    @Column(name = "error", length = 255)
    var error: String? = null,
) : BaseEntity() {
    fun markSent(ticketId: String) {
        transition(from = setOf(NotificationDispatchStatus.PENDING), to = NotificationDispatchStatus.SENT)
        this.ticketId = ticketId
    }

    fun markDelivered() {
        transition(from = setOf(NotificationDispatchStatus.SENT), to = NotificationDispatchStatus.DELIVERED)
    }

    fun markFailed(error: String) {
        transition(
            from = setOf(NotificationDispatchStatus.PENDING, NotificationDispatchStatus.SENT),
            to = NotificationDispatchStatus.FAILED,
        )
        this.error = error
    }

    private fun transition(from: Set<NotificationDispatchStatus>, to: NotificationDispatchStatus) {
        check(dispatchStatus in from) { "발송 상태 전이 불가: $dispatchStatus -> $to" }
        dispatchStatus = to
    }

    companion object {
        fun pending(notificationId: Long, notificationDeviceId: Long?, expoToken: String) = NotificationDispatch(
            notificationId = notificationId,
            notificationDeviceId = notificationDeviceId,
            expoToken = expoToken,
        )
    }
}
