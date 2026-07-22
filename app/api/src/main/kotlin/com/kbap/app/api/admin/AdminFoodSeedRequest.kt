package com.kbap.app.api.admin

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class AdminFoodSeedRequest(
    @field:NotNull
    @field:Size(max = MAX_SEED_NAMES, message = "koreanNames 는 최대 $MAX_SEED_NAMES 건까지 제출할 수 있습니다")
    val koreanNames: List<String>? = null,
) {
    companion object {
        const val MAX_SEED_NAMES = 500
    }
}
