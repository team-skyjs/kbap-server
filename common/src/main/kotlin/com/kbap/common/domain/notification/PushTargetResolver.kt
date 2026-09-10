package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationConsents
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PushTargetResolver(
    private val deviceRepository: NotificationDeviceJpaRepository,
    private val settingRepository: NotificationSettingJpaRepository,
    private val consentRepository: NotificationConsentJpaRepository,
) {
    @Transactional(readOnly = true)
    fun resolve(memberIds: Collection<Long>, type: NotificationType): List<NotificationDevice> {
        if (memberIds.isEmpty()) return emptyList()
        val devices = deviceRepository.findByMemberIdInAndTokenInvalidAtIsNull(memberIds)
        if (devices.isEmpty()) return emptyList()

        val settings = settingRepository.findByMemberIdIn(memberIds).associateBy { it.memberId }
        val consents = consentRepository.findOpenByMemberIdIn(memberIds).groupBy { it.memberId!! }

        fun marketingEnabled(memberId: Long) =
            NotificationConsents.isMarketingEnabled(consents[memberId].orEmpty(), MARKETING_CONSENT_REQUIRED_VERSION)

        fun toggledOn(memberId: Long): Boolean = when (type) {
            NotificationType.HELPFUL, NotificationType.REVIEW_REMINDER -> settings[memberId]?.activity == true
            NotificationType.MEAL_TIME -> settings[memberId]?.mealTime == true
            NotificationType.SCAN_SUGGESTION, NotificationType.NEWS -> true
        }

        fun allowed(memberId: Long): Boolean = toggledOn(memberId) && (!type.marketing || marketingEnabled(memberId))

        return devices.filter { allowed(it.memberId!!) }
    }

    companion object {
        const val MARKETING_CONSENT_REQUIRED_VERSION = 2
    }
}
