package com.kbap.api.admin

import java.math.BigDecimal
import java.time.LocalDateTime

data class AdminOrderSummaryResponse(
    val id: Long,
    val memberId: Long,
    val memberNickname: String?,
    val roadAddress: String?,
    val itemCount: Int,
    val totalQuantity: Int,
    val totalPrice: Int,
    val scanImageUrl: String?,
    val createdAt: LocalDateTime,
)

data class AdminOrderPageResponse(
    val items: List<AdminOrderSummaryResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminOrderItemResponse(
    val id: Long,
    val foodId: Long,
    val foodDisplayName: String?,
    val foodImageUrl: String?,
    val menuName: String,
    val quantity: Int,
    val price: Int?,
)

data class AdminOrderDetailResponse(
    val id: Long,
    val memberId: Long,
    val memberNickname: String?,
    val roadAddress: String?,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val scanImageUrl: String?,
    val totalQuantity: Int,
    val totalPrice: Int,
    val items: List<AdminOrderItemResponse>,
    val createdAt: LocalDateTime,
)

data class AdminOrderDeleteResponse(
    val id: Long,
    val deleted: Boolean,
    val deletedItemCount: Int,
)
