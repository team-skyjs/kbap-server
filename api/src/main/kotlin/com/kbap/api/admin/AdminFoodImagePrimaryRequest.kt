package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull

@Schema(description = "대표 이미지 교체 요청")
data class AdminFoodImagePrimaryRequest(
    @field:NotNull(message = "version 은 필수입니다")
    @field:Schema(description = "갤러리 조회로 받은 음식 낙관잠금 버전", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    val version: Long?,
)
