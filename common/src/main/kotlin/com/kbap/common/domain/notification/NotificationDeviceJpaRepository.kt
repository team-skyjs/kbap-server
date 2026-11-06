package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationDevice
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface NotificationDeviceJpaRepository : JpaRepository<NotificationDevice, Long> {
    fun findByInstallationId(installationId: String): NotificationDevice?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from NotificationDevice d where d.installationId = :installationId")
    fun findLockedByInstallationId(installationId: String): NotificationDevice?

    @Modifying
    @Query(
        value = """
            INSERT INTO notification_device (installation_id, member_id, expo_token, platform, lang, created_at, updated_at)
            VALUES (:installationId, :memberId, :expoToken, :platform, :lang, NOW(6), NOW(6))
            ON DUPLICATE KEY UPDATE id = id
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(installationId: String, memberId: Long?, expoToken: String, platform: String, lang: String): Int

    fun findByMemberId(memberId: Long): List<NotificationDevice>

    fun findByMemberIdInAndTokenInvalidAtIsNull(memberIds: Collection<Long>): List<NotificationDevice>
}
