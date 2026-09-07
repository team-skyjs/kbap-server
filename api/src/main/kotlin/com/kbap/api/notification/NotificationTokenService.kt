package com.kbap.api.notification

import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationDevice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationTokenService(
    private val deviceRepository: NotificationDeviceJpaRepository,
    private val consentRepository: NotificationConsentJpaRepository,
) {
    @Transactional
    fun registerToken(
        installationId: String,
        memberId: Long?,
        token: String,
        platform: DevicePlatform,
        lang: String,
    ) {
        val device = deviceRepository.findByInstallationId(installationId)
        if (device == null) {
            deviceRepository.save(NotificationDevice.register(installationId, token, platform, lang, memberId))
            return
        }
        device.renew(token, platform, lang)
        if (memberId != null) {
            device.linkMember(memberId)
        }
    }
}
