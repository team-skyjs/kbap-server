package com.kbap.api.order

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrderWriter(
    private val orderRepository: OrderJpaRepository,
    private val orderItemRepository: OrderItemJpaRepository,
) {
    @Transactional
    fun placeOrder(memberId: Long, request: OrderCreateRequest, roadAddress: String?): Long {
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

    companion object {
        private const val IMAGE_PATH_UNIQUE_KEY = "uq_orders_image_path"
    }
}
