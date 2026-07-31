package com.kbap.api.place

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

@Schema(description = "식당 검색 요청")
data class PlaceSearchRequest(
    @field:NotBlank(message = "query 는 필수입니다")
    @field:Schema(description = "검색 키워드(빈/공백 불가)", example = "한밥집", requiredMode = Schema.RequiredMode.REQUIRED)
    val query: String? = null,

    @field:Min(value = 1, message = "page 는 1 이상이어야 합니다")
    @field:Max(value = MAX_PAGE.toLong(), message = "page 는 $MAX_PAGE 이하여야 합니다")
    @field:Schema(description = "페이지 번호(1~$MAX_PAGE, 기본 1)", example = "1")
    val page: Int = 1,
) {
    companion object {
        const val MAX_PAGE = 45
    }
}
