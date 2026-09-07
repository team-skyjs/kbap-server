package com.kbap.api.notification

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationPreferences
import com.kbap.common.domain.notification.model.NotificationSetting
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class NotificationSettingsResult(
    val activity: Boolean,
    val newsEnabled: Boolean,
    val mealTime: Boolean,
    val privacyConsent: NotificationConsent?,
    val receiveConsent: NotificationConsent?,
)

@Service
class NotificationSettingService(
    private val settingRepository: NotificationSettingJpaRepository,
    private val consentRepository: NotificationConsentJpaRepository,
    private val consentService: NotificationConsentService,
    private val memberService: MemberService,
) {
    @Transactional(readOnly = true)
    fun getSettings(memberId: Long): NotificationSettingsResult {
        memberService.getMember(memberId)
        return assemble(memberId)
    }

    @Transactional
    fun updateSettings(memberId: Long, installationId: String?, request: NotificationSettingsUpdateRequest): NotificationSettingsResult {
        memberService.getMember(memberId)
        val now = LocalDateTime.now()

        if (request.activity != null) {
            settingOf(memberId).updateActivity(request.activity)
        }

        val news = request.news
        if (news != null) {
            if (news.enabled == true) {
                val versions = mapOf(
                    NotificationConsentType.MARKETING_PRIVACY to news.privacyConsentVersion!!,
                    NotificationConsentType.MARKETING_RECEIVE to news.receiveConsentVersion!!,
                )
                consentService.grantForMember(memberId, installationId, versions, now)
            }
            if (news.enabled == false) {
                consentService.revokeForMember(memberId, now)
            }
            if (news.mealTime != null) {
                if (news.mealTime && !isNewsEnabled(memberId)) {
                    throw BusinessException(ErrorCode.MARKETING_CONSENT_REQUIRED)
                }
                settingOf(memberId).updateMealTime(news.mealTime)
            }
        }

        return assemble(memberId)
    }

    private fun settingOf(memberId: Long): NotificationSetting =
        settingRepository.findByMemberId(memberId)
            ?: settingRepository.save(NotificationSetting.defaultFor(memberId))

    private fun isNewsEnabled(memberId: Long): Boolean =
        consentService.isMarketingEnabled(consentRepository.findOpenByMemberId(memberId))

    private fun assemble(memberId: Long): NotificationSettingsResult {
        val preferences = settingRepository.findByMemberId(memberId)?.preferences() ?: NotificationPreferences.DEFAULT
        val openConsents = consentRepository.findOpenByMemberId(memberId)
        val enabled = consentService.isMarketingEnabled(openConsents)

        val privacyConsent = openConsents
            .filter { it.consentType == NotificationConsentType.MARKETING_PRIVACY }
            .maxByOrNull { it.grantedAt }
        val receiveConsent = openConsents
            .filter { it.consentType == NotificationConsentType.MARKETING_RECEIVE }
            .maxByOrNull { it.grantedAt }

        return NotificationSettingsResult(
            activity = preferences.activity,
            newsEnabled = enabled,
            mealTime = preferences.mealTime && enabled,
            privacyConsent = privacyConsent,
            receiveConsent = receiveConsent,
        )
    }
}
