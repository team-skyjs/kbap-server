package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationDevice
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationDeviceJpaRepository : JpaRepository<NotificationDevice, Long> {
    fun findByInstallationId(installationId: String): NotificationDevice?

    fun findByMemberId(memberId: Long): List<NotificationDevice>

    fun findByMemberIdInAndTokenInvalidAtIsNull(memberIds: Collection<Long>): List<NotificationDevice>
}
