package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class AdminOrderService(
    private val orderRepository: OrderJpaRepository,
    private val orderItemRepository: OrderItemJpaRepository,
    private val memberRepository: MemberJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val auditRecorder: AdminAuditRecorder,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getOrderPage(memberId: Long?, from: LocalDate?, to: LocalDate?, page: Int, size: Int): AdminOrderPageResponse {
        val result = orderRepository.findAdminPage(memberId, from?.atStartOfDay(), to?.plusDays(1)?.atStartOfDay(), PageRequest.of(page - 1, size))
        val orders = result.content
        val itemsByOrder = if (orders.isEmpty()) emptyMap() else orderItemRepository.findByOrderIdInOrderByIdAsc(orders.map { it.id }).groupBy { it.orderId }
        val nicknames = memberRepository.findAllById(orders.map { it.memberId }.toSet()).associate { it.id to it.nickname }
        return AdminOrderPageResponse(
            items = orders.map { order ->
                val items = itemsByOrder[order.id].orEmpty()
                AdminOrderSummaryResponse(
                    id = order.id,
                    memberId = order.memberId,
                    memberNickname = nicknames[order.memberId],
                    roadAddress = order.roadAddress,
                    itemCount = items.size,
                    totalQuantity = OrderItem.totalQuantityOf(items),
                    totalPrice = OrderItem.totalPriceOf(items),
                    scanImageUrl = ImageUrls.resolve(imagePublicBaseUrl, order.imagePath),
                    createdAt = order.createdAt,
                )
            },
            page = page,
            size = size,
            totalCount = result.totalElements,
            totalPages = totalPagesOf(result.totalElements, size),
        )
    }

    @Transactional(readOnly = true)
    fun getOrderDetail(orderId: Long): AdminOrderDetailResponse {
        val order = getOrder(orderId)
        val items = orderItemRepository.findByOrderIdOrderByIdAsc(order.id)
        val foods = foodRepository.findAllById(items.map { it.foodId }.toSet()).associateBy { it.id }
        return AdminOrderDetailResponse(
            id = order.id,
            memberId = order.memberId,
            memberNickname = memberRepository.findById(order.memberId).orElse(null)?.nickname,
            roadAddress = order.roadAddress,
            latitude = order.latitude,
            longitude = order.longitude,
            scanImageUrl = ImageUrls.resolve(imagePublicBaseUrl, order.imagePath),
            totalQuantity = OrderItem.totalQuantityOf(items),
            totalPrice = OrderItem.totalPriceOf(items),
            items = items.map {
                val food = foods[it.foodId]
                AdminOrderItemResponse(
                    id = it.id,
                    foodId = it.foodId,
                    foodDisplayName = food?.displayName,
                    foodImageUrl = ImageUrls.resolve(imagePublicBaseUrl, food?.imageRef),
                    menuName = it.menuName,
                    quantity = it.quantity,
                    price = it.price,
                )
            },
            createdAt = order.createdAt,
        )
    }

    @Transactional
    fun deleteOrder(adminId: Long, orderId: Long): AdminOrderDeleteResponse {
        val order = getOrder(orderId)
        val items = orderItemRepository.findByOrderIdOrderByIdAsc(order.id)
        items.forEach { it.delete() }
        order.delete()
        auditRecorder.record(
            adminId, AdminAuditAction.ORDER_DELETE, AdminAuditTargetType.ORDER, order.id,
            mapOf("deleted" to false), mapOf("deleted" to true, "deletedItemCount" to items.size),
        )
        return AdminOrderDeleteResponse(id = order.id, deleted = true, deletedItemCount = items.size)
    }

    private fun getOrder(orderId: Long): Order =
        orderRepository.findById(orderId).orElseThrow { BusinessException(ErrorCode.ORDER_NOT_FOUND) }
}
