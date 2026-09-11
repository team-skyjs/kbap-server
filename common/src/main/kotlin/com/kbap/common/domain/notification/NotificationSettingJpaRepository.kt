package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationSetting
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationSettingJpaRepository : JpaRepository<NotificationSetting, Long> {
    fun findByMemberIdAndInstallationId(memberId: Long, installationId: String): NotificationSetting?

    fun findByMemberId(memberId: Long): List<NotificationSetting>

    fun findByMemberIdIn(memberIds: Collection<Long>): List<NotificationSetting>
}
