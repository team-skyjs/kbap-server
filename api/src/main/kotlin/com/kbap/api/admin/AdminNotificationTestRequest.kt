package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

@Schema(description = "관리자 테스트 푸시 발송 요청")
data class AdminNotificationTestRequest(
    @field:NotNull
    @field:Positive
    @field:Schema(description = "발송 대상 회원 id", example = "35", requiredMode = Schema.RequiredMode.REQUIRED)
    val memberId: Long? = null,
)
