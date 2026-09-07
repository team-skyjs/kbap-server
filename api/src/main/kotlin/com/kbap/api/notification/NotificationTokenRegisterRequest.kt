package com.kbap.api.notification

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

@Schema(description = "푸시 토큰 등록·갱신 요청")
data class NotificationTokenRegisterRequest(
    @field:NotBlank(message = "token 은 필수입니다")
    @field:Size(max = 255, message = "token 은 255자 이하여야 합니다")
    @field:Schema(description = "Expo push token", example = "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]", requiredMode = Schema.RequiredMode.REQUIRED)
    val token: String?,

    @field:NotBlank(message = "platform 은 필수입니다")
    @field:Pattern(regexp = "(?i)ios|android", message = "platform 은 ios 또는 android 여야 합니다")
    @field:Schema(description = "기기 플랫폼 — ios 또는 android(대소문자 무관)", example = "ios", requiredMode = Schema.RequiredMode.REQUIRED)
    val platform: String?,

    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Size(max = 10, message = "lang 은 10자 이하여야 합니다")
    @field:Schema(description = "기기 언어 코드 — 검증·정규화 없이 저장", example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String?,

    @field:Valid
    @field:Schema(description = "게스트 K-Bap 소식 동의(두 동의 버전). 회원 요청에서는 무시된다(회원 동의는 설정 API)")
    val settings: MarketingSettingsRequest? = null,
)

@Schema(description = "게스트 K-Bap 소식 동의 설정 — 마케팅 목적 개인정보 수집·이용 동의와 광고성 정보 수신 동의를 함께 받는다")
data class MarketingSettingsRequest(
    @field:NotNull(message = "marketing 은 필수입니다")
    @field:Schema(description = "K-Bap 소식(광고성 알림) 수신 동의 on/off", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    val marketing: Boolean?,

    @field:Positive(message = "privacyConsentVersion 은 양의 정수여야 합니다")
    @field:Max(value = MAX_CONSENT_VERSION, message = "privacyConsentVersion 은 $MAX_CONSENT_VERSION 이하여야 합니다")
    @field:Schema(description = "마케팅 목적 개인정보 수집·이용 동의 문구 버전(1~65535). marketing 이 true 면 필수", example = "1")
    val privacyConsentVersion: Int? = null,

    @field:Positive(message = "receiveConsentVersion 은 양의 정수여야 합니다")
    @field:Max(value = MAX_CONSENT_VERSION, message = "receiveConsentVersion 은 $MAX_CONSENT_VERSION 이하여야 합니다")
    @field:Schema(description = "광고성 정보 수신 동의 문구 버전(1~65535). marketing 이 true 면 필수", example = "1")
    val receiveConsentVersion: Int? = null,
) {
    @get:AssertTrue(message = "marketing 이 true 면 privacyConsentVersion·receiveConsentVersion 이 모두 필요합니다")
    @get:Schema(hidden = true)
    val versionsPresentWhenOptedIn: Boolean
        get() = marketing != true || (privacyConsentVersion != null && receiveConsentVersion != null)

    companion object {
        const val MAX_CONSENT_VERSION = 65535L
    }
}
