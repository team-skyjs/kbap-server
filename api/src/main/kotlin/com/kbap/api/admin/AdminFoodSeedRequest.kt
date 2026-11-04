package com.kbap.api.admin

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class AdminFoodSeedRequest(
    @field:NotNull
    @field:Size(max = MAX_SEED_NAMES, message = "koreanNames 는 최대 $MAX_SEED_NAMES 건까지 제출할 수 있습니다")
    val koreanNames: List<String>? = null,
    @field:io.swagger.v3.oas.annotations.media.Schema(description = "true 면 음식 행만 만들고 콘텐츠 생성 요청(랭체인)을 쌓지 않는다 — 사람이 PUT 으로 내용을 채우고 승인할 때(KB-993). 기본 false(지금처럼 쌓는다)", nullable = true)
    val skipPipeline: Boolean? = null,
) {
    companion object {
        const val MAX_SEED_NAMES = 500
    }
}
