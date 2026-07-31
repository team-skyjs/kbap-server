package com.kbap.api.block

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull

@Schema(description = "회원 차단 요청")
data class MemberBlockRequest(
    @field:NotNull
    @field:Schema(description = "차단할 회원 id", example = "42")
    val memberId: Long?,
)
