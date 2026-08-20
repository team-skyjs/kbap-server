package com.kbap.common.domain.order.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.ZoneId

@Entity
@Table(
    name = "orders",
    indexes = [Index(name = "idx_orders_recent", columnList = "member_id, created_at")],
)
class Order(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "image_path", nullable = false, length = 512, unique = true)
    var imagePath: String = "",

    @Column(precision = 10, scale = 7)
    var latitude: BigDecimal? = null,

    @Column(precision = 10, scale = 7)
    var longitude: BigDecimal? = null,

    @Column(name = "road_address", length = MAX_ADDRESS_LENGTH)
    var roadAddress: String? = null,
) : BaseEntity() {
    fun orderedAt(): Long = createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        const val MAX_ADDRESS_LENGTH = 200
        val LATITUDE_RANGE = BigDecimal("-90")..BigDecimal("90")
        val LONGITUDE_RANGE = BigDecimal("-180")..BigDecimal("180")

        fun place(
            memberId: Long,
            imagePath: String,
            latitude: BigDecimal?,
            longitude: BigDecimal?,
            roadAddress: String?,
        ): Order {
            require(imagePath.isNotBlank()) { "imagePath 는 blank 일 수 없습니다" }
            require((latitude == null) == (longitude == null)) { "위도·경도는 함께 있거나 함께 없어야 합니다" }
            require(latitude == null || latitude in LATITUDE_RANGE) { "위도는 -90~90 이어야 합니다: $latitude" }
            require(longitude == null || longitude in LONGITUDE_RANGE) { "경도는 -180~180 이어야 합니다: $longitude" }
            require(roadAddress == null || roadAddress.length <= MAX_ADDRESS_LENGTH) { "주소는 최대 ${MAX_ADDRESS_LENGTH}자입니다" }
            return Order(
                memberId = memberId,
                imagePath = imagePath,
                latitude = latitude,
                longitude = longitude,
                roadAddress = roadAddress,
            )
        }
    }
}
