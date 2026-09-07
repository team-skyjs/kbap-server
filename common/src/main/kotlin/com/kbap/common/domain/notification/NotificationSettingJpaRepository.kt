package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationSetting
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationSettingJpaRepository : JpaRepository<NotificationSetting, Long> {
    fun findByMemberId(memberId: Long): NotificationSetting?
}
