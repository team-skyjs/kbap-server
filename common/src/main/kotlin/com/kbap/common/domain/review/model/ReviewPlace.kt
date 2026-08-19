package com.kbap.common.domain.review.model

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import java.math.BigDecimal

@Embeddable
class ReviewPlace(
    @Enumerated(EnumType.STRING)
    @Column(name = "place_source", length = 20)
    val source: PlaceSource? = null,

    @Column(name = "place_id", length = MAX_PLACE_ID_LENGTH)
    val placeId: String? = null,

    @Column(name = "place_name", length = MAX_NAME_LENGTH)
    val name: String? = null,

    @Column(name = "place_address", length = MAX_ADDRESS_LENGTH)
    val address: String? = null,

    @Column(name = "place_address_ko", length = MAX_ADDRESS_LENGTH)
    val addressKo: String? = null,

    @Column(name = "place_latitude", precision = COORDINATE_PRECISION, scale = COORDINATE_SCALE)
    val latitude: BigDecimal? = null,

    @Column(name = "place_longitude", precision = COORDINATE_PRECISION, scale = COORDINATE_SCALE)
    val longitude: BigDecimal? = null,
) {
    init {
        val placeIdValue = placeId
        val nameValue = name
        val addressValue = address
        val latitudeValue = latitude
        val longitudeValue = longitude
        require(placeIdValue == null || placeIdValue.length <= MAX_PLACE_ID_LENGTH) { "placeId 는 최대 ${MAX_PLACE_ID_LENGTH}자입니다" }
        require(nameValue == null || nameValue.length <= MAX_NAME_LENGTH) { "식당명은 최대 ${MAX_NAME_LENGTH}자입니다" }
        require(addressValue == null || addressValue.length <= MAX_ADDRESS_LENGTH) { "주소는 최대 ${MAX_ADDRESS_LENGTH}자입니다" }
        require(latitudeValue == null || latitudeValue in LATITUDE_RANGE) { "위도는 -90~90 이어야 합니다: $latitudeValue" }
        require(longitudeValue == null || longitudeValue in LONGITUDE_RANGE) { "경도는 -180~180 이어야 합니다: $longitudeValue" }
    }

    fun isEmpty(): Boolean =
        placeId == null && name == null && address == null && latitude == null && longitude == null

    companion object {
        const val MAX_PLACE_ID_LENGTH = 255
        const val MAX_NAME_LENGTH = 100
        const val MAX_ADDRESS_LENGTH = 200
        const val COORDINATE_PRECISION = 10
        const val COORDINATE_SCALE = 7
        val LATITUDE_RANGE = BigDecimal("-90")..BigDecimal("90")
        val LONGITUDE_RANGE = BigDecimal("-180")..BigDecimal("180")
    }
}
