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
    val kbapNewsEnabled: Boolean,
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
        var setting = settingRepository.findByMemberId(memberId)
        fun settingOrCreate(): NotificationSetting =
            setting ?: settingRepository.save(NotificationSetting.defaultFor(memberId)).also { setting = it }

        request.activity?.let { settingOrCreate().updateActivity(it) }
        request.kbapNews?.let { news ->
            when (news.enabled) {
                true -> consentService.grantForMember(
                    memberId,
                    installationId,
                    mapOf(
                        NotificationConsentType.MARKETING_PRIVACY to news.privacyConsentVersion!!,
                        NotificationConsentType.MARKETING_RECEIVE to news.receiveConsentVersion!!,
                    ),
                    now,
                )
                false -> consentService.revokeForMember(memberId, now)
                null -> Unit
            }
            news.mealTime?.let { enabled ->
                if (enabled && !consentService.isMarketingEnabled(consentRepository.findOpenByMemberId(memberId))) {
                    throw BusinessException(ErrorCode.MARKETING_CONSENT_REQUIRED)
                }
                settingOrCreate().updateMealTime(enabled)
            }
        }
        return assemble(memberId)
    }

    private fun assemble(memberId: Long): NotificationSettingsResult {
        val preferences = settingRepository.findByMemberId(memberId)?.preferences() ?: NotificationPreferences.DEFAULT
        val open = consentRepository.findOpenByMemberId(memberId)
        val enabled = consentService.isMarketingEnabled(open)
        return NotificationSettingsResult(
            activity = preferences.activity,
            kbapNewsEnabled = enabled,
            mealTime = preferences.mealTime && enabled,
            privacyConsent = open.latestOf(NotificationConsentType.MARKETING_PRIVACY),
            receiveConsent = open.latestOf(NotificationConsentType.MARKETING_RECEIVE),
        )
    }

    private fun List<NotificationConsent>.latestOf(type: NotificationConsentType): NotificationConsent? =
        filter { it.consentType == type }.maxByOrNull { it.grantedAt }
}
