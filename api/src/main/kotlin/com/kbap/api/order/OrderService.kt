package com.kbap.api.order

import com.kbap.api.food.FoodService
import com.kbap.api.image.ImageUploadService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import com.kbap.common.util.CursorParser
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrderService(
    private val orderRepository: OrderJpaRepository,
    private val orderItemRepository: OrderItemJpaRepository,
    private val imageUploadService: ImageUploadService,
    private val foodRepository: FoodJpaRepository,
    private val foodService: FoodService,
) {
    @Transactional
    fun createOrder(memberId: Long, request: OrderCreateRequest, roadAddress: String?): Long {
        imageUploadService.verifyImageAccess(memberId, request.imagePath!!)
            ?: throw BusinessException(ErrorCode.SCAN_IMAGE_NOT_VERIFIED)
        val order = try {
            orderRepository.saveAndFlush(request.toOrder(memberId, roadAddress))
        } catch (e: DataIntegrityViolationException) {
            throw BusinessException(ErrorCode.ORDER_ALREADY_PLACED)
        }
        orderItemRepository.saveAll(request.items.map { it.toItem(order.id) })
        return order.id
    }

    @Transactional(readOnly = true)
    fun getOrderPage(memberId: Long, rawCursor: String?, size: Int): OrderListPage {
        val cursor = CursorParser.parse(rawCursor)
        val pageSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val orders = orderRepository.findPageByMemberId(memberId, cursor, PageRequest.of(0, pageSize + 1))
        val hasNext = orders.size > pageSize
        val pageOrders = orders.take(pageSize)
        return OrderListPage(
            totalCount = orderRepository.countByMemberId(memberId),
            items = summarize(pageOrders),
            hasNext = hasNext,
            nextCursor = pageOrders.lastOrNull()?.id?.toString()?.takeIf { hasNext },
        )
    }

    @Transactional(readOnly = true)
    fun getOrderDetail(memberId: Long, orderId: Long): OrderDetailResponse {
        val order = orderRepository.findById(orderId)
            .filter { it.memberId == memberId }
            .orElseThrow { BusinessException(ErrorCode.ORDER_NOT_FOUND) }
        val items = orderItemRepository.findByOrderIdOrderByIdAsc(order.id)
        val thumbnailsByFoodId = resolveThumbnails(items)
        return OrderDetailResponse(
            orderId = order.id,
            orderedAt = order.orderedAt(),
            roadAddress = order.roadAddress,
            totalQuantity = items.sumOf { it.quantity },
            totalPrice = items.sumOf { (it.price ?: 0) * it.quantity },
            thumbnails = items.take(MAX_THUMBNAILS).mapNotNull { thumbnailsByFoodId[it.foodId] },
            items = items.map {
                OrderItemResponse(menuName = it.menuName, quantity = it.quantity, price = it.price, foodId = it.foodId)
            },
        )
    }

    private fun summarize(orders: List<Order>): List<OrderSummaryResponse> {
        if (orders.isEmpty()) return emptyList()
        val itemsByOrderId = orderItemRepository
            .findByOrderIdInOrderByIdAsc(orders.map { it.id })
            .groupBy { it.orderId }
        val thumbnailsByFoodId = resolveThumbnails(itemsByOrderId.values.flatten())
        return orders.map { order ->
            val items = itemsByOrderId[order.id].orEmpty()
            OrderSummaryResponse(
                orderId = order.id,
                orderedAt = order.orderedAt(),
                roadAddress = order.roadAddress,
                totalQuantity = items.sumOf { it.quantity },
                thumbnails = items.take(MAX_THUMBNAILS).mapNotNull { thumbnailsByFoodId[it.foodId] },
            )
        }
    }

    private fun resolveThumbnails(items: List<OrderItem>): Map<Long, String> {
        val foodIds = items.map { it.foodId }.distinct()
        if (foodIds.isEmpty()) return emptyMap()
        val foodsById = foodRepository.findAllById(foodIds).associateBy { it.id }
        return foodIds.associateWith { foodService.resolveImageUrlOrDefault(foodsById[it]) }
    }

    companion object {
        const val MAX_PAGE_SIZE = 30
        private const val MAX_THUMBNAILS = 4
    }
}
