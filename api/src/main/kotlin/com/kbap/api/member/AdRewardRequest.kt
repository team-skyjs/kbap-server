package com.kbap.api.member

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AdRewardRequest(
    @field:NotBlank(message = "rewardKey 는 필수입니다")
    @field:Size(max = 128, message = "rewardKey 는 128자 이하여야 합니다")
    @field:Schema(description = "광고 SDK 보상 콜백의 고유값 — 같은 값 재요청은 적립하지 않는다", example = "admob-txn-7f3c2a91", requiredMode = Schema.RequiredMode.REQUIRED)
    val rewardKey: String?,
    @field:NotBlank(message = "adUnitId 는 필수입니다")
    @field:Size(max = 100, message = "adUnitId 는 100자 이하여야 합니다")
    @field:Schema(description = "보상을 준 광고 단위 ID", example = "ca-app-pub-3940256099942544/5224354917", requiredMode = Schema.RequiredMode.REQUIRED)
    val adUnitId: String?,
)
