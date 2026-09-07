package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationConsent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface NotificationConsentJpaRepository : JpaRepository<NotificationConsent, Long> {
    @Query("select c from NotificationConsent c where c.memberId = :memberId and c.revokedAt is null")
    fun findOpenByMemberId(@Param("memberId") memberId: Long): List<NotificationConsent>

    @Query(
        """
        select c from NotificationConsent c
        where c.installationId = :installationId and c.memberId is null and c.revokedAt is null
        """,
    )
    fun findOpenGuestByInstallationId(@Param("installationId") installationId: String): List<NotificationConsent>

    @Modifying
    @Query("update NotificationConsent c set c.revokedAt = :now where c.memberId = :memberId and c.revokedAt is null")
    fun closeOpenByMemberId(@Param("memberId") memberId: Long, @Param("now") now: LocalDateTime): Int
}
