package com.kbap.api.notification

import com.kbap.api.member.MemberService
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.DevicePlatform
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class NotificationTokenService(
    private val deviceRepository: NotificationDeviceJpaRepository,
    private val consentRepository: NotificationConsentJpaRepository,
    private val settingRepository: NotificationSettingJpaRepository,
    private val memberService: MemberService,
) {
    @Transactional
    fun registerToken(installationId: String, memberId: Long?, token: String, platform: DevicePlatform, lang: String) {
        memberId?.let { memberService.getMember(it) }
        deviceRepository.insertIfAbsent(installationId, memberId, token, platform.name, lang)
        val device = checkNotNull(deviceRepository.findLockedByInstallationId(installationId))
        if (memberId == null) {
            if (device.memberId == null) device.renew(token, platform, lang)
            return
        }
        device.renew(token, platform, lang)
        device.linkMember(memberId)
    }

    @Transactional
    fun linkOnLogin(installationId: String, memberId: Long) {
        deviceRepository.findByInstallationId(installationId)?.linkMember(memberId)
    }

    @Transactional
    fun unlinkOnLogout(installationId: String) {
        deviceRepository.findByInstallationId(installationId)?.unlinkMember()
    }

    @Transactional
    fun closeOnWithdraw(memberId: Long) {
        deviceRepository.findByMemberId(memberId).forEach { it.unlinkMember() }
        settingRepository.findByMemberId(memberId).forEach { it.delete() }
        consentRepository.closeOpenByMemberId(memberId, LocalDateTime.now())
    }
}
