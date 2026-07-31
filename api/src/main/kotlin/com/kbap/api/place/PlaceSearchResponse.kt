package com.kbap.api.place

import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchResult
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

@Schema(description = "식당 검색 결과")
data class PlaceSearchResponse(
    @field:Schema(description = "검색된 식당 목록(없으면 빈 배열)")
    val items: List<PlaceItemResponse>,

    @field:Schema(description = "다음 페이지 존재 여부", example = "true")
    val hasNext: Boolean,
) {
    companion object {
        fun from(result: PlaceSearchResult): PlaceSearchResponse =
            PlaceSearchResponse(
                items = result.items.map(PlaceItemResponse::from),
                hasNext = result.hasNext,
            )
    }
}

@Schema(description = "검색된 식당 — 이 값을 리뷰 작성 요청의 place 로 그대로 보낸다")
data class PlaceItemResponse(
    @field:Schema(description = "식당명", example = "한밥집 강남점")
    val name: String,

    @field:Schema(description = "주소(도로명 우선, 없으면 지번)", example = "서울 강남구 테헤란로 123")
    val address: String?,

    @field:Schema(description = "카카오 장소 id", example = "27290047")
    val kakaoPlaceId: String?,

    @field:Schema(description = "위도", example = "37.4979502")
    val latitude: BigDecimal?,

    @field:Schema(description = "경도", example = "127.0276368")
    val longitude: BigDecimal?,
) {
    companion object {
        fun from(place: FoundPlace): PlaceItemResponse =
            PlaceItemResponse(
                name = place.name,
                address = place.address,
                kakaoPlaceId = place.kakaoPlaceId,
                latitude = place.latitude,
                longitude = place.longitude,
            )
    }
}
