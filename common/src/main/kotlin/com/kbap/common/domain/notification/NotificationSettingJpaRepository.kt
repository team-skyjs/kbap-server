package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface NotificationSettingJpaRepository : JpaRepository<NotificationSetting, Long> {
    fun findByMemberIdAndInstallationId(memberId: Long, installationId: String): NotificationSetting?

    fun findByMemberId(memberId: Long): List<NotificationSetting>

    fun findByMemberIdIn(memberIds: Collection<Long>): List<NotificationSetting>

    @Query(
        """
        select distinct s.memberId from NotificationSetting s
        where s.news = true
          and s.memberId > :afterMemberId
          and not exists (
            select 1 from Notification n
            where n.memberId = s.memberId and n.type = :type and n.createdAt >= :since
          )
        order by s.memberId
        """,
    )
    fun findNewsMemberIdsNotNotifiedSince(
        @Param("type") type: NotificationType,
        @Param("since") since: LocalDateTime,
        @Param("afterMemberId") afterMemberId: Long,
        limit: Limit,
    ): List<Long>
}
