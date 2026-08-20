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
        verifyOrderable(memberId, request)
        return saveOrder(memberId, request, roadAddress)
    }

    private fun verifyOrderable(memberId: Long, request: OrderCreateRequest) {
        imageUploadService.verifyImageAccess(memberId, request.imagePath!!)
            ?: throw BusinessException(ErrorCode.SCAN_IMAGE_NOT_VERIFIED)
        if (orderRepository.existsByImagePath(request.imagePath)) {
            throw BusinessException(ErrorCode.ORDER_ALREADY_PLACED)
        }
        val foodIds = request.items.map { it.foodId!! }.distinct()
        if (foodService.getReadyFoodsByIds(foodIds).size != foodIds.size) {
            throw BusinessException(ErrorCode.ORDER_INVALID)
        }
    }

    private fun saveOrder(memberId: Long, request: OrderCreateRequest, roadAddress: String?): Long {
        val order = try {
            orderRepository.saveAndFlush(request.toOrder(memberId, roadAddress))
        } catch (e: DataIntegrityViolationException) {
            if (isImagePathConflict(e)) throw BusinessException(ErrorCode.ORDER_ALREADY_PLACED)
            throw e
        }
        orderItemRepository.saveAll(request.items.map { it.toItem(order.id) })
        return order.id
    }

    private fun isImagePathConflict(e: DataIntegrityViolationException): Boolean =
        generateSequence(e as Throwable) { it.cause }
            .any { it.message?.contains(IMAGE_PATH_UNIQUE_KEY) == true }

    @Transactional(readOnly = true)
    fun getOrderPage(memberId: Long, rawCursor: String?, size: Int): OrderListPage {
        val cursor = CursorParser.parse(rawCursor)
        val pageSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val orders = orderRepository.findPageByMemberId(memberId, cursor, PageRequest.of(0, pageSize + 1))
        val hasNext = orders.size > pageSize
        val pageOrders = orders.take(pageSize)
        return OrderListPage(
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
        val imageRefsByFoodId = resolveThumbnails(items)
        return OrderDetailResponse(
            orderId = order.id,
            orderedAt = order.orderedAt(),
            roadAddress = order.roadAddress,
            totalQuantity = OrderItem.totalQuantityOf(items),
            totalPrice = OrderItem.totalPriceOf(items),
            items = items.map {
                OrderItemResponse(
                    menuName = it.menuName,
                    quantity = it.quantity,
                    price = it.price,
                    foodId = it.foodId,
                    imageRef = imageRefsByFoodId.getValue(it.foodId),
                )
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
                totalQuantity = OrderItem.totalQuantityOf(items),
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
        private const val IMAGE_PATH_UNIQUE_KEY = "uq_orders_image_path"
        private const val MAX_THUMBNAILS = 4
    }
}
