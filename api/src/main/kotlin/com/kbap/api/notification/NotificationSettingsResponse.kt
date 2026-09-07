package com.kbap.api.notification

import com.kbap.common.domain.notification.model.NotificationConsent
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "회원 알림 설정 — 활동 푸시·K-Bap에서 보내는 소식 두 그룹")
data class NotificationSettingsResponse(
    @field:Schema(description = "활동/소식(리뷰 도움됨·리뷰 작성 리마인더) 수신. 설정 기록이 없으면 true", example = "true")
    val activity: Boolean,

    @field:Schema(description = "K-Bap에서 보내는 소식(광고성 푸시) 그룹")
    val kbapNews: KbapNewsResponse,
) {
    companion object {
        fun from(result: NotificationSettingsResult) =
            NotificationSettingsResponse(
                activity = result.activity,
                kbapNews = KbapNewsResponse(
                    enabled = result.kbapNewsEnabled,
                    mealTime = result.mealTime,
                    privacyConsent = result.privacyConsent?.let(ConsentResponse::from),
                    receiveConsent = result.receiveConsent?.let(ConsentResponse::from),
                ),
            )
    }
}

@Schema(description = "K-Bap에서 보내는 소식 그룹 상태")
data class KbapNewsResponse(
    @field:Schema(description = "마케팅 목적 개인정보 수집·이용 동의와 광고성 정보 수신 동의가 모두 유효한지", example = "true")
    val enabled: Boolean,

    @field:Schema(description = "식사 시간 알림(점심·저녁 넛지) 수신. enabled 가 false 면 저장값과 무관하게 false", example = "true")
    val mealTime: Boolean,

    @field:Schema(description = "마케팅 목적 개인정보 수집·이용 동의 — 열린 최신 1건. 없으면 null", nullable = true)
    val privacyConsent: ConsentResponse?,

    @field:Schema(description = "광고성 정보 수신 동의 — 열린 최신 1건. 없으면 null", nullable = true)
    val receiveConsent: ConsentResponse?,
)

@Schema(description = "동의 1건")
data class ConsentResponse(
    @field:Schema(description = "동의한 문구 버전", example = "1")
    val version: Int,

    @field:Schema(description = "동의 시각(서버 스탬프)", example = "2026-09-07T12:00:00")
    val grantedAt: LocalDateTime,
) {
    companion object {
        fun from(consent: NotificationConsent) = ConsentResponse(version = consent.consentVersion, grantedAt = consent.grantedAt)
    }
}
