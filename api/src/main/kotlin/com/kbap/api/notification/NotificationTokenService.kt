package com.kbap.api.notification

import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationDevice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

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
        settings: MarketingSettingsRequest? = null,
    ) {
        upsertDevice(installationId, memberId, token, platform, lang)
        if (memberId == null && settings != null) {
            applyGuestMarketingConsent(installationId, settings)
        }
    }

    @Transactional
    fun linkOnLogin(installationId: String, memberId: Long) {
        deviceRepository.findByInstallationId(installationId)?.linkMember(memberId)

        val guestConsents = consentRepository.findOpenGuestByInstallationId(installationId)
        if (guestConsents.isEmpty()) {
            return
        }
        if (consentRepository.findOpenByMemberId(memberId).isEmpty()) {
            guestConsents.forEach { it.claim(memberId) }
        } else {
            val now = LocalDateTime.now()
            guestConsents.forEach { it.revoke(now) }
        }
    }

    @Transactional
    fun unlinkOnLogout(installationId: String) {
        deviceRepository.findByInstallationId(installationId)?.unlinkMember()
    }

    @Transactional
    fun closeOnWithdraw(memberId: Long) {
        deviceRepository.findByMemberId(memberId).forEach { it.unlinkMember() }
        consentRepository.closeOpenByMemberId(memberId, LocalDateTime.now())
    }

    private fun upsertDevice(installationId: String, memberId: Long?, token: String, platform: DevicePlatform, lang: String) {
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

    private fun applyGuestMarketingConsent(installationId: String, settings: MarketingSettingsRequest) {
        val now = LocalDateTime.now()
        val open = consentRepository.findOpenGuestByInstallationId(installationId)
        if (settings.marketing != true) {
            open.forEach { it.revoke(now) }
            return
        }
        val version = settings.marketingConsentVersion!!
        open.filter { it.consentVersion != version }.forEach { it.revoke(now) }
        if (open.none { it.consentVersion == version }) {
            consentRepository.save(NotificationConsent.grantForInstallation(installationId, version, now))
        }
    }
}
