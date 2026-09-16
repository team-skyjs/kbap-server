package com.kbap.api.notification

import com.kbap.common.domain.notification.model.Notification
import java.time.ZoneId

data class NotificationResponse(
    val id: Long,
    val title: String,
    val body: String,
    val receivedAt: Long,
    val read: Boolean,
) {
    companion object {
        fun from(notification: Notification) = NotificationResponse(
            id = notification.id,
            title = notification.title,
            body = notification.body,
            receivedAt = notification.createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            read = notification.isRead(),
        )
    }
}
