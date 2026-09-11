package com.kbap.api.notification

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
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
)
