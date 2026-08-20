package com.kbap.api.order

import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal

@Schema(description = "주문 저장 요청 — 스캔 결과 화면에서 고른 메뉴들")
data class OrderCreateRequest(
    @field:NotBlank(message = "imagePath 는 필수입니다")
    @field:Size(max = 512, message = "imagePath 는 최대 512자입니다")
    @field:Schema(description = "스캔 식별자 — 스캔 요청에 썼던 이미지 경로 그대로. 스캔 1회당 주문 1회", example = "scan/42/menu.jpg")
    val imagePath: String? = null,

    @field:NotEmpty(message = "주문 항목은 1개 이상이어야 합니다")
    @field:Size(max = 50, message = "주문 항목은 최대 50개입니다")
    @field:Valid
    @field:Schema(description = "주문한 메뉴 목록")
    val items: List<OrderItemRequest> = emptyList(),

    @field:DecimalMin(value = "-90", message = "위도는 -90 이상이어야 합니다")
    @field:DecimalMax(value = "90", message = "위도는 90 이하여야 합니다")
    @field:Schema(description = "주문 순간 위도 — 저장용, 응답에 노출되지 않는다", example = "37.5636000", nullable = true)
    val latitude: BigDecimal? = null,

    @field:DecimalMin(value = "-180", message = "경도는 -180 이상이어야 합니다")
    @field:DecimalMax(value = "180", message = "경도는 180 이하여야 합니다")
    @field:Schema(description = "주문 순간 경도", example = "126.9834000", nullable = true)
    val longitude: BigDecimal? = null,
) {
    @get:jakarta.validation.constraints.AssertTrue(message = "latitude·longitude 는 함께 보내거나 함께 생략해야 합니다")
    @get:Schema(hidden = true)
    val coordinatesComplete: Boolean
        get() = (latitude == null) == (longitude == null)

    fun toOrder(memberId: Long, roadAddress: String?): Order =
        Order.place(
            memberId = memberId,
            imagePath = imagePath!!,
            latitude = latitude,
            longitude = longitude,
            roadAddress = roadAddress,
        )
}

@Schema(description = "주문 항목 — 저장 시점 스냅샷")
data class OrderItemRequest(
    @field:NotNull(message = "foodId 는 필수입니다")
    @field:Positive(message = "foodId 는 양수여야 합니다")
    @field:Schema(description = "음식 식별자 — 스캔 응답의 foodId 그대로", example = "7")
    val foodId: Long? = null,

    @field:NotBlank(message = "menuName 은 필수입니다")
    @field:Size(max = 100, message = "menuName 은 최대 100자입니다")
    @field:Schema(description = "메뉴명 스냅샷", example = "순두부찌개")
    val menuName: String? = null,

    @field:NotNull(message = "quantity 는 필수입니다")
    @field:Min(value = 1, message = "quantity 는 1 이상이어야 합니다")
    @field:Max(value = MAX_QUANTITY, message = "quantity 는 $MAX_QUANTITY 이하여야 합니다")
    @field:Schema(description = "수량", example = "2")
    val quantity: Int? = null,

    @field:Min(value = 0, message = "price 는 0 이상이어야 합니다")
    @field:Max(value = MAX_PRICE, message = "price 는 $MAX_PRICE 이하여야 합니다")
    @field:Schema(description = "단가 스냅샷(원화). 스캔에서 가격 미인식이면 생략", example = "9000", nullable = true)
    val price: Int? = null,
) {
    fun toItem(orderId: Long): OrderItem =
        OrderItem.place(orderId = orderId, foodId = foodId!!, menuName = menuName!!, quantity = quantity!!, price = price)

    companion object {
        const val MAX_QUANTITY = 999L
        const val MAX_PRICE = 10_000_000L
    }
}
