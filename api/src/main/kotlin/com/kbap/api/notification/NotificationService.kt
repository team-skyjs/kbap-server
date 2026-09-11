package com.kbap.api.notification

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
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
class NotificationService(
    private val notificationRepository: NotificationJpaRepository,
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
                settingOf(memberId).updateMealTime(true)
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
        settingRepository.findByMemberIdAndInstallationIdIsNull(memberId)
            ?: settingRepository.save(NotificationSetting.defaultFor(memberId))

    private fun isNewsEnabled(memberId: Long): Boolean =
        consentService.isMarketingEnabled(consentRepository.findOpenByMemberId(memberId))

    private fun assemble(memberId: Long): NotificationSettingsResult {
        val setting = settingRepository.findByMemberIdAndInstallationIdIsNull(memberId) ?: NotificationSetting.defaultFor(memberId)
        val openConsents = consentRepository.findOpenByMemberId(memberId)
        val enabled = consentService.isMarketingEnabled(openConsents)

        return NotificationSettingsResult(
            activity = setting.activity,
            newsEnabled = enabled,
            mealTime = setting.mealTime && enabled,
            privacyConsent = latestOpen(openConsents, NotificationConsentType.MARKETING_PRIVACY),
            receiveConsent = latestOpen(openConsents, NotificationConsentType.MARKETING_RECEIVE),
        )
    }

    @Transactional(readOnly = true)
    fun getDeviceSettings(memberId: Long, installationId: String): NotificationSettingsResult {
        memberService.getMember(memberId)
        return assembleDevice(memberId, installationId)
    }

    @Transactional
    fun updateDeviceSettings(
        memberId: Long,
        installationId: String,
        request: DeviceNotificationSettingsUpdateRequest,
    ): NotificationSettingsResult {
        memberService.getMember(memberId)
        val now = LocalDateTime.now()

        if (request.activity != null) {
            deviceSettingOf(memberId, installationId).updateActivity(request.activity)
        }

        val news = request.news
        if (news != null) {
            if (news.consent == true) {
                val versions = mapOf(
                    NotificationConsentType.MARKETING_PRIVACY to news.privacyConsentVersion!!,
                    NotificationConsentType.MARKETING_RECEIVE to news.receiveConsentVersion!!,
                )
                consentService.grantForMember(memberId, installationId, versions, now)
            }
            if (news.consent == false) {
                consentService.revokeForMember(memberId, now)
            }
            if (news.enabled != null) {
                deviceSettingOf(memberId, installationId).updateNews(news.enabled)
            }
            if (news.mealTime != null) {
                val setting = deviceSettingOf(memberId, installationId)
                if (news.mealTime && !setting.news) {
                    throw BusinessException(ErrorCode.MARKETING_CONSENT_REQUIRED)
                }
                setting.updateMealTime(news.mealTime)
            }
        }

        return assembleDevice(memberId, installationId)
    }

    private fun deviceSettingOf(memberId: Long, installationId: String): NotificationSetting =
        settingRepository.findByMemberIdAndInstallationId(memberId, installationId)
            ?: settingRepository.save(NotificationSetting.defaultFor(memberId, installationId))

    private fun assembleDevice(memberId: Long, installationId: String): NotificationSettingsResult {
        val setting = settingRepository.findByMemberIdAndInstallationId(memberId, installationId)
            ?: NotificationSetting.defaultFor(memberId, installationId)
        val openConsents = consentRepository.findOpenByMemberId(memberId)

        return NotificationSettingsResult(
            activity = setting.activity,
            newsEnabled = setting.news,
            mealTime = setting.mealTime && setting.news,
            privacyConsent = latestOpen(openConsents, NotificationConsentType.MARKETING_PRIVACY),
            receiveConsent = latestOpen(openConsents, NotificationConsentType.MARKETING_RECEIVE),
        )
    }

    private fun latestOpen(open: List<NotificationConsent>, type: NotificationConsentType): NotificationConsent? =
        open.filter { it.consentType == type }.maxByOrNull { it.grantedAt }

    @Transactional(readOnly = true)
    fun getRecentNotifications(memberId: Long, installationId: String): List<NotificationResponse> {
        memberService.getMember(memberId)
        val since = LocalDateTime.now().minusDays(RECENT_DAYS)
        return notificationRepository.findByMemberIdAndInstallationIdAndCreatedAtAfterOrderByIdDesc(memberId, installationId, since)
            .map(NotificationResponse::from)
    }

    @Transactional
    fun markRead(memberId: Long, installationId: String, notificationId: Long): NotificationResponse {
        memberService.getMember(memberId)
        val notification = notificationRepository.findByIdAndMemberIdAndInstallationId(notificationId, memberId, installationId)
            ?: throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        notification.markRead(LocalDateTime.now())
        return NotificationResponse.from(notification)
    }

    companion object {
        const val RECENT_DAYS = 7L
    }
}
