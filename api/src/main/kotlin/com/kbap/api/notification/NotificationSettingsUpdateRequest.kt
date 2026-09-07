package com.kbap.api.notification

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Positive

@Schema(description = "회원 알림 설정 부분 수정 — 보낸 항목만 반영되고 나머지는 유지된다")
data class NotificationSettingsUpdateRequest(
    @field:Schema(description = "활동/소식(리뷰 도움됨·리뷰 작성 리마인더) 수신 on/off", example = "false")
    val activity: Boolean? = null,

    @field:Valid
    @field:Schema(description = "K-Bap에서 보내는 소식 그룹 수정")
    val news: NewsUpdateRequest? = null,
)

@Schema(description = "K-Bap에서 보내는 소식 그룹 부분 수정. 처리 순서: enabled → mealTime")
data class NewsUpdateRequest(
    @field:Schema(description = "true = 두 동의 기록(켜기, 두 버전 필수), false = 두 동의 철회(끄기). 없으면 동의 상태 유지", example = "true")
    val enabled: Boolean? = null,

    @field:Schema(description = "식사 시간 알림 on/off. true 는 (이 요청의 enabled 반영 후) 소식이 켜져 있어야 한다 — 아니면 NOTIFICATION-001", example = "true")
    val mealTime: Boolean? = null,

    @field:Positive(message = "privacyConsentVersion 은 양의 정수여야 합니다")
    @field:Max(value = MarketingSettingsRequest.MAX_CONSENT_VERSION, message = "privacyConsentVersion 은 ${MarketingSettingsRequest.MAX_CONSENT_VERSION} 이하여야 합니다")
    @field:Schema(description = "마케팅 목적 개인정보 수집·이용 동의 문구 버전(1~65535). enabled 가 true 면 필수", example = "1")
    val privacyConsentVersion: Int? = null,

    @field:Positive(message = "receiveConsentVersion 은 양의 정수여야 합니다")
    @field:Max(value = MarketingSettingsRequest.MAX_CONSENT_VERSION, message = "receiveConsentVersion 은 ${MarketingSettingsRequest.MAX_CONSENT_VERSION} 이하여야 합니다")
    @field:Schema(description = "광고성 정보 수신 동의 문구 버전(1~65535). enabled 가 true 면 필수", example = "1")
    val receiveConsentVersion: Int? = null,
) {
    @get:AssertTrue(message = "enabled 가 true 면 privacyConsentVersion·receiveConsentVersion 이 모두 필요합니다")
    @get:Schema(hidden = true)
    val versionsPresentWhenEnabled: Boolean
        get() = enabled != true || (privacyConsentVersion != null && receiveConsentVersion != null)
}
