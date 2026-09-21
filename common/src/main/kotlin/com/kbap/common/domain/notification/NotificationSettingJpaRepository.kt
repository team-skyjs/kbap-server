package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationSetting
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface NotificationSettingJpaRepository : JpaRepository<NotificationSetting, Long> {
    fun findByMemberIdAndInstallationId(memberId: Long, installationId: String): NotificationSetting?

    fun findByMemberId(memberId: Long): List<NotificationSetting>

    fun findByMemberIdIn(memberIds: Collection<Long>): List<NotificationSetting>

    @Query("select distinct s.memberId from NotificationSetting s where s.news = true and s.memberId > :afterMemberId order by s.memberId")
    fun findMemberIdsByNewsTrueAfter(@Param("afterMemberId") afterMemberId: Long, limit: Limit): List<Long>
}
