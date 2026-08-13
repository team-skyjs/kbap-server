package com.kbap.api.place

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
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
)
