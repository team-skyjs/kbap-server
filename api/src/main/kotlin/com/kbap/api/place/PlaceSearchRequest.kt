package com.kbap.api.place

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

@Schema(description = "주변 식당 검색 요청")
data class PlaceSearchRequest(
    @field:NotNull(message = "latitude 는 필수입니다")
    @field:DecimalMin(value = "-90", message = "latitude 는 -90 이상이어야 합니다")
    @field:DecimalMax(value = "90", message = "latitude 는 90 이하여야 합니다")
    @field:Schema(description = "사용자 위치 위도(-90~90)", example = "37.4979502", requiredMode = Schema.RequiredMode.REQUIRED)
    val latitude: BigDecimal? = null,

    @field:NotNull(message = "longitude 는 필수입니다")
    @field:DecimalMin(value = "-180", message = "longitude 는 -180 이상이어야 합니다")
    @field:DecimalMax(value = "180", message = "longitude 는 180 이하여야 합니다")
    @field:Schema(description = "사용자 위치 경도(-180~180)", example = "127.0276368", requiredMode = Schema.RequiredMode.REQUIRED)
    val longitude: BigDecimal? = null,

    @field:Min(value = 1, message = "page 는 1 이상이어야 합니다")
    @field:Max(value = MAX_PAGE.toLong(), message = "page 는 $MAX_PAGE 이하여야 합니다")
    @field:Schema(description = "페이지 번호(1~$MAX_PAGE, 기본 1)", example = "1")
    val page: Int = 1,
) {
    companion object {
        const val MAX_PAGE = 45
    }
}
