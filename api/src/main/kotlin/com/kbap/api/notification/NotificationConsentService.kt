package com.kbap.api.notification

import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class NotificationConsentService(
    private val consentRepository: NotificationConsentJpaRepository,
) {
    @Transactional
    fun grantForMember(memberId: Long, installationId: String?, versions: Map<NotificationConsentType, Int>, now: LocalDateTime) {
        grant(consentRepository.findOpenByMemberId(memberId), versions, now) { type, version ->
            NotificationConsent.grantForMember(memberId, installationId, type, version, now)
        }
    }

    @Transactional
    fun revokeForMember(memberId: Long, now: LocalDateTime) {
        consentRepository.closeOpenByMemberId(memberId, now)
    }

    private fun grant(
        open: List<NotificationConsent>,
        versions: Map<NotificationConsentType, Int>,
        now: LocalDateTime,
        newConsent: (NotificationConsentType, Int) -> NotificationConsent,
    ) {
        val openByType = open.groupBy { it.consentType }
        versions.forEach { (type, version) ->
            val rows = openByType[type].orEmpty()
            rows.filter { it.consentVersion != version }.forEach { it.revoke(now) }
            if (rows.none { it.consentVersion == version }) {
                consentRepository.save(newConsent(type, version))
            }
        }
    }
}
