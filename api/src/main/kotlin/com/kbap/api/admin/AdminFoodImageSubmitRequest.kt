package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "이미지 배치 제출 요청")
data class AdminFoodImageSubmitRequest(
    @field:Schema(
        description = "제출할 음식 id 목록. 생략하면 이미지가 없는 음식 전체를 일괄 제출한다",
        example = "[1, 2, 3]",
        nullable = true,
    )
    val foodIds: List<Long>? = null,
)
