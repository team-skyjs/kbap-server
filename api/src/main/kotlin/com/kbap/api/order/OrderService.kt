package com.kbap.api.order

import com.kbap.common.domain.LanguageCode
import com.kbap.api.food.FoodService
import com.kbap.api.scanbatch.ScanBatchService
import com.kbap.common.domain.scan.ScanOrderJpaRepository
import com.kbap.common.domain.scan.model.ScanOrder
import com.kbap.common.domain.order.OrderPhotoJpaRepository
import com.kbap.common.domain.order.OrderItemSourceJpaRepository
import com.kbap.common.domain.order.model.OrderPhoto
import com.kbap.common.domain.order.model.OrderItemSource
import java.security.MessageDigest
import java.util.UUID
import com.kbap.api.image.ImageUploadService
import com.kbap.api.image.UploadedImageService
import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import com.kbap.common.domain.order.model.OrderPlaceSnapshot
import com.kbap.common.util.BotImagePaths
import com.kbap.common.util.CursorParser
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
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
    private val uploadedImageService: UploadedImageService,
    private val memberService: MemberService,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
    private val scanBatchService: ScanBatchService,
    private val scanOrders: ScanOrderJpaRepository,
    private val orderPhotos: OrderPhotoJpaRepository,
    private val orderItemSources: OrderItemSourceJpaRepository,
) {
    @Transactional
    fun createOrder(
        memberId: Long,
        request: OrderCreateRequest,
        roadAddress: String?,
        resolvedPlace: OrderPlaceSnapshot?,
    ): Long {
        memberService.getMemberForShare(memberId)
        request.batchId?.let { return createBatchOrder(memberId, it, request, roadAddress, resolvedPlace) }
        verifyOrderable(memberId, request)
        return saveOrder(memberId, request, roadAddress, resolvedPlace)
    }

    private fun createBatchOrder(memberId: Long, batchId: String, request: OrderCreateRequest, roadAddress: String?, resolvedPlace: OrderPlaceSnapshot?): Long {
        val plan = scanBatchService.planOrder(memberId, batchId, request.items.map { it.mergedItemId to it.foodId!! })
        verifyFoodsExist(request)
        if (scanOrders.existsByOperationId(plan.operationId)) throw BusinessException(ErrorCode.ORDER_ALREADY_PLACED)
        val firstPhoto = plan.photos.first().objectKey
        if (orderRepository.existsByImagePath(firstPhoto)) throw BusinessException(ErrorCode.ORDER_ALREADY_PLACED)
        val order = try {
            orderRepository.saveAndFlush(Order.create(memberId, firstPhoto, request.latitude, request.longitude, roadAddress, resolvedPlace))
        } catch (e: DataIntegrityViolationException) {
            if (isImagePathConflict(e)) throw BusinessException(ErrorCode.ORDER_ALREADY_PLACED)
            throw e
        }
        val items = orderItemRepository.saveAll(request.items.map { it.toItem(order.id) })
        try {
            scanOrders.saveAndFlush(
                ScanOrder(
                    operationId = plan.operationId,
                    orderId = order.id,
                    requestKey = ScanBatchService.bytesOf(UUID.randomUUID()),
                    requestHash = MessageDigest.getInstance("SHA-256").digest(request.items.joinToString("|") { "${it.mergedItemId}:${it.foodId}:${it.quantity}:${it.price}" }.toByteArray()),
                    resultRevision = maxOf(1, plan.resultRevision),
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw BusinessException(ErrorCode.ORDER_ALREADY_PLACED)
        }
        orderPhotos.saveAll(plan.photos.map { OrderPhoto(order.id, it.id, it.selectionIndex, it.objectKey) })
        orderItemSources.saveAll(items.zip(plan.itemOccurrenceIds).flatMap { (item, occurrenceIds) -> occurrenceIds.map { OrderItemSource(item.id, it) } })
        return order.id
    }

    private fun verifyOrderable(memberId: Long, request: OrderCreateRequest) {
        request.imagePath?.let { verifyScanImage(memberId, it) }
        verifyFoodsExist(request)
    }

    private fun verifyFoodsExist(request: OrderCreateRequest) {
        val foodIds = request.items.map { it.foodId!! }.distinct()
        if (foodRepository.findByIdIn(foodIds).size != foodIds.size) {
            throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        }
    }

    private fun verifyScanImage(memberId: Long, imagePath: String) {
        if (BotImagePaths.isProtected(imagePath) || UploadedImageJpaRepository.isCleanupTarget(imagePath)) {
            throw BusinessException(ErrorCode.SCAN_IMAGE_NOT_VERIFIED)
        }
        imageUploadService.verifyImageAccess(memberId, imagePath)
            ?: throw BusinessException(ErrorCode.SCAN_IMAGE_NOT_VERIFIED)
        if (orderRepository.existsByImagePath(imagePath)) {
            throw BusinessException(ErrorCode.ORDER_ALREADY_PLACED)
        }
    }

    private fun saveOrder(
        memberId: Long,
        request: OrderCreateRequest,
        roadAddress: String?,
        resolvedPlace: OrderPlaceSnapshot?,
    ): Long {
        val order = try {
            orderRepository.saveAndFlush(request.toOrder(memberId, roadAddress, resolvedPlace))
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

    @Transactional
    fun updatePlace(memberId: Long, orderId: Long, request: OrderPlaceUpdateRequest, lang: LanguageCode? = null): OrderDetailResponse {
        memberService.getMemberForShare(memberId)
        getOwnOrder(memberId, orderId).replacePlace(request.toSnapshot())
        return getOrderDetail(memberId, orderId, lang)
    }

    @Transactional
    fun replaceItemImage(memberId: Long, orderId: Long, itemId: Long, imagePath: String, lang: LanguageCode? = null): OrderDetailResponse {
        val item = getOwnItem(memberId, orderId, itemId)
        if (!uploadedImageService.ownsAllImages(memberId, listOf(imagePath), UploadPurpose.ORDER_ITEM)) {
            throw BusinessException(ErrorCode.ORDER_ITEM_IMAGE_NOT_VERIFIED)
        }
        item.replaceImage(imagePath)
        return getOrderDetail(memberId, orderId, lang)
    }

    @Transactional
    fun restoreItemImage(memberId: Long, orderId: Long, itemId: Long, lang: LanguageCode? = null): OrderDetailResponse {
        getOwnItem(memberId, orderId, itemId).restoreCatalogImage()
        return getOrderDetail(memberId, orderId, lang)
    }

    private fun getOwnOrder(memberId: Long, orderId: Long): Order =
        orderRepository.findById(orderId)
            .filter { it.memberId == memberId }
            .orElseThrow { BusinessException(ErrorCode.ORDER_NOT_FOUND) }

    private fun getOwnItem(memberId: Long, orderId: Long, itemId: Long): OrderItem {
        val order = getOwnOrder(memberId, orderId)
        return orderItemRepository.findById(itemId)
            .filter { it.orderId == order.id }
            .orElseThrow { BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND) }
    }

    @Transactional(readOnly = true)
    fun getOrderDetail(memberId: Long, orderId: Long, lang: LanguageCode? = null): OrderDetailResponse {
        val order = getOwnOrder(memberId, orderId)
        val items = orderItemRepository.findByOrderIdOrderByIdAsc(order.id)
        val foodsById = loadFoodsById(items)
        return OrderDetailResponse(
            orderId = order.id,
            orderedAt = order.orderedAt(),
            roadAddress = order.roadAddress,
            totalQuantity = OrderItem.totalQuantityOf(items),
            totalPrice = OrderItem.totalPriceOf(items),
            scanImageUrl = ImageUrls.resolve(imagePublicBaseUrl, order.imagePath),
            scanImageUrls = orderPhotos.findByOrderIdOrderBySelectionIndex(order.id).map { it.objectKey }
                .ifEmpty { listOfNotNull(order.imagePath) }
                .mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
            place = OrderPlaceResponse.from(order.resolvedPlace),
            items = items.map {
                val food = foodsById[it.foodId]
                val photoUrl = food?.takeIf { f -> f.isReady() }?.let(foodService::resolveImageUrl)
                OrderItemResponse(
                    id = it.id,
                    menuName = it.menuName,
                    name = lang?.let { l -> food?.takeIf { f -> f.isReady() }?.displayName(l) },
                    quantity = it.quantity,
                    price = it.price,
                    foodId = it.foodId,
                    imageRef = photoUrl ?: foodService.resolveImageUrlOrDefault(null),
                    ready = food?.isReady() == true,
                    hasPhoto = photoUrl != null,
                    userImageUrl = userImageUrlOf(it),
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
                thumbnails = items.take(MAX_THUMBNAILS).mapNotNull { userImageUrlOf(it) ?: thumbnailsByFoodId[it.foodId] },
                scanImageUrl = ImageUrls.resolve(imagePublicBaseUrl, order.imagePath),
                place = OrderPlaceResponse.from(order.resolvedPlace),
            )
        }
    }

    private fun resolveThumbnails(items: List<OrderItem>): Map<Long, String> {
        val foodsById = loadFoodsById(items)
        return items.map { it.foodId }.distinct().associateWith { publicImageUrlOf(foodsById[it]) }
    }

    private fun userImageUrlOf(item: OrderItem): String? = ImageUrls.resolve(imagePublicBaseUrl, item.imagePath)

    private fun publicImageUrlOf(food: Food?): String =
        foodService.resolveImageUrlOrDefault(food?.takeIf { it.isReady() })

    private fun loadFoodsById(items: List<OrderItem>): Map<Long, Food> {
        val foodIds = items.map { it.foodId }.distinct()
        if (foodIds.isEmpty()) return emptyMap()
        return foodRepository.findAllById(foodIds).associateBy { it.id }
    }

    companion object {
        const val MAX_PAGE_SIZE = 30
        private const val IMAGE_PATH_UNIQUE_KEY = "uq_orders_image_path"
        private const val MAX_THUMBNAILS = 4
    }
}
