package com.kbap.api.place

import com.kbap.common.port.place.FoundPlace
import com.kbap.common.port.place.PlaceSearchPage
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

@Schema(description = "주변 식당 탑10 응답 — 가까운 순 최대 10건")
data class PlaceNearbyResponse(
    @field:Schema(description = "주변 식당 목록(없으면 빈 배열)")
    val items: List<PlaceItemResponse>,
) {
    companion object {
        fun from(places: List<FoundPlace>): PlaceNearbyResponse =
            PlaceNearbyResponse(items = places.map(PlaceItemResponse::from))
    }
}

@Schema(description = "식당 키워드 검색 응답 — 가까운 순, 페이지당 최대 15건")
data class PlaceSearchPageResponse(
    @field:Schema(description = "검색된 식당 목록(없으면 빈 배열)")
    val items: List<PlaceItemResponse>,

    @field:Schema(description = "다음 페이지 존재 여부", example = "true")
    val hasNext: Boolean,
) {
    companion object {
        fun from(page: PlaceSearchPage): PlaceSearchPageResponse =
            PlaceSearchPageResponse(
                items = page.items.map(PlaceItemResponse::from),
                hasNext = page.hasNext,
            )
    }
}

@Schema(description = "검색된 식당 — 이 값을 리뷰 작성 요청의 place 로 그대로 보낸다")
data class PlaceItemResponse(
    @field:Schema(description = "식당명", example = "한밥집 강남점")
    val name: String,

    @field:Schema(description = "주소(도로명 우선, 없으면 지번)", example = "서울 강남구 테헤란로 123")
    val address: String?,

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
                latitude = place.latitude,
                longitude = place.longitude,
            )
    }
}
