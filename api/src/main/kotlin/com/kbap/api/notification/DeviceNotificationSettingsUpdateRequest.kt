package com.kbap.api.notification

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Positive

@Schema(description = "이 기기의 알림 설정 부분 수정(X-API-Version 2.1 이상) — 보낸 항목만 반영되고 나머지는 유지된다")
data class DeviceNotificationSettingsUpdateRequest(
    @field:Schema(description = "활동 알림(리뷰 도움됨·리뷰 작성 리마인더) 수신 on/off — 이 기기", example = "false")
    val activity: Boolean? = null,

    @field:Valid
    @field:Schema(description = "K-Bap에서 보내는 소식 그룹 수정 — 기기 수신(enabled)과 회원 동의(consent)는 별개 항목")
    val news: DeviceNewsUpdateRequest? = null,
)

@Schema(description = "소식 그룹 부분 수정. 처리 순서: consent → enabled → mealTime")
data class DeviceNewsUpdateRequest(
    @field:Schema(description = "이 기기의 광고성 소식 수신 on/off. 동의 원장은 건드리지 않는다", example = "true")
    val enabled: Boolean? = null,

    @field:Schema(description = "회원 마케팅 수신 동의. true = 두 동의 기록(두 버전 필수, 같은 버전이면 무변화·다른 버전이면 재동의), false = 회원의 열린 동의 전부 철회. 기기 토글값은 바뀌지 않는다", example = "true")
    val consent: Boolean? = null,

    @field:Schema(description = "식사 시간 알림 on/off — 이 기기. true 는 (이 요청 반영 후) 이 기기 소식이 켜져 있어야 한다 — 아니면 NOTIFICATION-001", example = "true")
    val mealTime: Boolean? = null,

    @field:Positive(message = "privacyConsentVersion 은 양의 정수여야 합니다")
    @field:Max(value = NewsUpdateRequest.MAX_CONSENT_VERSION, message = "privacyConsentVersion 은 ${NewsUpdateRequest.MAX_CONSENT_VERSION} 이하여야 합니다")
    @field:Schema(description = "마케팅 목적 개인정보 수집·이용 동의 문구 버전(1~65535). consent 가 true 면 필수", example = "2")
    val privacyConsentVersion: Int? = null,

    @field:Positive(message = "receiveConsentVersion 은 양의 정수여야 합니다")
    @field:Max(value = NewsUpdateRequest.MAX_CONSENT_VERSION, message = "receiveConsentVersion 은 ${NewsUpdateRequest.MAX_CONSENT_VERSION} 이하여야 합니다")
    @field:Schema(description = "광고성 정보 수신 동의 문구 버전(1~65535). consent 가 true 면 필수", example = "2")
    val receiveConsentVersion: Int? = null,
) {
    @get:AssertTrue(message = "consent 가 true 면 privacyConsentVersion·receiveConsentVersion 이 모두 필요합니다")
    @get:Schema(hidden = true)
    val versionsPresentWhenConsenting: Boolean
        get() = consent != true || (privacyConsentVersion != null && receiveConsentVersion != null)
}
