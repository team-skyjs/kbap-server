package com.kbap.common.domain.order.model

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

@Embeddable
class OrderPlaceSnapshot(
    @Enumerated(EnumType.STRING)
    @Column(name = "place_source", length = 20)
    val source: OrderPlaceSource = OrderPlaceSource.GOOGLE_PLACE,

    @Column(name = "place_external_id", length = MAX_EXTERNAL_ID_LENGTH)
    val externalId: String = "",

    @Column(name = "place_name", length = MAX_NAME_LENGTH)
    val name: String = "",

    @Column(name = "place_address", length = MAX_ADDRESS_LENGTH)
    val address: String? = null,

    @Column(name = "place_language", length = MAX_LANGUAGE_LENGTH)
    val language: String = "",
) {
    init {
        require(source == OrderPlaceSource.GOOGLE_PLACE) { "source 는 GOOGLE_PLACE 만 허용합니다: $source" }
        require(externalId.length <= MAX_EXTERNAL_ID_LENGTH) { "externalId 는 최대 ${MAX_EXTERNAL_ID_LENGTH}자입니다" }
        require(name.length <= MAX_NAME_LENGTH) { "name 은 최대 ${MAX_NAME_LENGTH}자입니다" }
        val addressValue = address
        require(addressValue == null || addressValue.length <= MAX_ADDRESS_LENGTH) { "address 는 최대 ${MAX_ADDRESS_LENGTH}자입니다" }
        require(language.length <= MAX_LANGUAGE_LENGTH) { "language 는 최대 ${MAX_LANGUAGE_LENGTH}자입니다" }
    }

    companion object {
        const val MAX_EXTERNAL_ID_LENGTH = 255
        const val MAX_NAME_LENGTH = 100
        const val MAX_ADDRESS_LENGTH = 200
        const val MAX_LANGUAGE_LENGTH = 7
    }
}
