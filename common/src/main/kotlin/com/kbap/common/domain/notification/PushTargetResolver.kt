package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationConsents
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationSetting
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

        val settings = settingRepository.findByMemberIdIn(memberIds).associateBy { it.memberId to it.installationId }
        val consents = consentRepository.findOpenByMemberIdIn(memberIds).groupBy { it.memberId!! }

        fun marketingEnabled(memberId: Long) =
            NotificationConsents.isMarketingEnabled(consents[memberId].orEmpty(), MARKETING_CONSENT_REQUIRED_VERSION)

        fun toggledOn(setting: NotificationSetting?): Boolean = when (type) {
            NotificationType.HELPFUL, NotificationType.REVIEW_REMINDER -> setting?.activity == true
            NotificationType.MEAL_TIME -> setting?.mealTime == true
            NotificationType.SCAN_SUGGESTION, NotificationType.NEWS, NotificationType.FOOD_DISCOVERY -> setting?.news == true
            NotificationType.OPERATOR_CHAT -> true
        }

        fun allowed(device: NotificationDevice): Boolean {
            val memberId = device.memberId!!
            return toggledOn(settings[memberId to device.installationId]) && (!type.marketing || marketingEnabled(memberId))
        }

        return devices.filter(::allowed)
    }

    @Transactional(readOnly = true)
    fun resolveGuests(installationIds: Collection<String>, type: NotificationType): List<NotificationDevice> {
        if (installationIds.isEmpty() || type != NotificationType.OPERATOR_CHAT) return emptyList()
        return installationIds.distinct().mapNotNull { deviceRepository.findByInstallationId(it) }
            .filter { it.memberId == null && it.tokenInvalidAt == null }
    }

    companion object {
        const val MARKETING_CONSENT_REQUIRED_VERSION = 2
    }
}
