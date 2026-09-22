package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "이미지 배치 제출 요청")
data class AdminFoodImageSubmitRequest(
    @field:Schema(
        description = "제출할 음식 id 목록. **생략하거나 null 이면 후보 전체가 제출된다(유료 API 전량 호출)**. " +
            "빈 배열은 아무것도 제출하지 않는다. 지정한 id 중 이미지 후보가 아닌 음식은 조용히 빠진다.",
        example = "[1, 2, 3]",
        nullable = true,
    )
    val foodIds: List<Long>? = null,
)
