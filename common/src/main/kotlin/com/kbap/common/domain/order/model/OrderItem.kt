package com.kbap.common.domain.order.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "order_item",
    indexes = [Index(name = "idx_order_item_order", columnList = "order_id")],
)
class OrderItem(
    @Column(name = "order_id", nullable = false)
    var orderId: Long = 0,

    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,

    @Column(name = "menu_name", nullable = false, length = MAX_MENU_NAME_LENGTH)
    var menuName: String = "",

    @Column(nullable = false)
    var quantity: Int = 1,

    @Column
    var price: Int? = null,
) : BaseEntity() {
    companion object {
        const val MAX_MENU_NAME_LENGTH = 100

        fun place(orderId: Long, foodId: Long, menuName: String, quantity: Int, price: Int?): OrderItem {
            require(menuName.isNotBlank()) { "menuName 은 blank 일 수 없습니다" }
            require(menuName.length <= MAX_MENU_NAME_LENGTH) { "menuName 은 최대 ${MAX_MENU_NAME_LENGTH}자입니다" }
            require(quantity >= 1) { "quantity 는 1 이상이어야 합니다: $quantity" }
            require(price == null || price >= 0) { "price 는 0 이상이어야 합니다: $price" }
            return OrderItem(orderId = orderId, foodId = foodId, menuName = menuName, quantity = quantity, price = price)
        }
    }
}
