package com.kbap.api.notification

import com.kbap.api.member.MemberService
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationDevice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class NotificationTokenService(
    private val deviceRepository: NotificationDeviceJpaRepository,
    private val consentRepository: NotificationConsentJpaRepository,
    private val consentService: NotificationConsentService,
    private val memberService: MemberService,
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
        memberId?.let { memberService.getMember(it) }
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
        val memberOpenTypes = consentRepository.findOpenByMemberId(memberId).map { it.consentType }.toSet()
        val now = LocalDateTime.now()
        guestConsents.forEach { if (it.consentType in memberOpenTypes) it.revoke(now) else it.claim(memberId) }
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
        if (settings.marketing != true) {
            consentService.revokeForInstallation(installationId, now)
            return
        }
        consentService.grantForInstallation(
            installationId,
            mapOf(
                NotificationConsentType.MARKETING_PRIVACY to settings.privacyConsentVersion!!,
                NotificationConsentType.MARKETING_RECEIVE to settings.receiveConsentVersion!!,
            ),
            now,
        )
    }
}
