package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationDispatch
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface NotificationDispatchJpaRepository : JpaRepository<NotificationDispatch, Long> {
    fun findByNotificationId(notificationId: Long): List<NotificationDispatch>

    fun findByNotificationIdIn(notificationIds: Collection<Long>): List<NotificationDispatch>

    @Query(
        """
        select d from NotificationDispatch d
        where d.dispatchStatus = com.kbap.common.domain.notification.model.NotificationDispatchStatus.SENT
          and d.notificationType in :types
          and d.createdAt between :from and :to
          and d.id > :afterId
        order by d.id
        """,
    )
    fun findSentForReceiptCheck(
        @Param("types") types: Collection<NotificationType>,
        @Param("from") from: LocalDateTime,
        @Param("to") to: LocalDateTime,
        @Param("afterId") afterId: Long,
        limit: Limit,
    ): List<NotificationDispatch>

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update NotificationDispatch d
        set d.dispatchStatus = com.kbap.common.domain.notification.model.NotificationDispatchStatus.FAILED, d.error = :error
        where d.dispatchStatus = com.kbap.common.domain.notification.model.NotificationDispatchStatus.SENT
          and d.createdAt < :before
        """,
    )
    fun failSentBefore(@Param("before") before: LocalDateTime, @Param("error") error: String): Int
}
