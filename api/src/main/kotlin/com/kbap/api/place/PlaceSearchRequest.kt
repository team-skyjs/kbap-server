package com.kbap.api.place

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal

private const val LANG_DESCRIPTION =
    "결과 식당명·주소 언어 코드. 지원: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. " +
        "지원 목록에 없는 값은 en 으로 응답하며, 요청 언어 번역이 없으면 현지어(한국어)+음역으로 내려간다."

@Schema(description = "주변 식당 조회 요청")
data class PlaceNearbyRequest(
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

    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(description = LANG_DESCRIPTION, example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String? = null,
)

@Schema(description = "식당 키워드 검색 요청")
data class PlaceSearchRequest(
    @field:NotBlank(message = "query 는 필수입니다")
    @field:Size(max = 100, message = "query 는 최대 100자입니다")
    @field:Schema(description = "검색 키워드(빈/공백 불가)", example = "마리김밥", requiredMode = Schema.RequiredMode.REQUIRED)
    val query: String? = null,

    @field:NotNull(message = "latitude 는 필수입니다")
    @field:DecimalMin(value = "-90", message = "latitude 는 -90 이상이어야 합니다")
    @field:DecimalMax(value = "90", message = "latitude 는 90 이하여야 합니다")
    @field:Schema(description = "사용자 위치 위도(-90~90) — 결과 위치 바이어스 기준", example = "37.4979502", requiredMode = Schema.RequiredMode.REQUIRED)
    val latitude: BigDecimal? = null,

    @field:NotNull(message = "longitude 는 필수입니다")
    @field:DecimalMin(value = "-180", message = "longitude 는 -180 이상이어야 합니다")
    @field:DecimalMax(value = "180", message = "longitude 는 180 이하여야 합니다")
    @field:Schema(description = "사용자 위치 경도(-180~180)", example = "127.0276368", requiredMode = Schema.RequiredMode.REQUIRED)
    val longitude: BigDecimal? = null,

    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(description = LANG_DESCRIPTION, example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String? = null,
)
