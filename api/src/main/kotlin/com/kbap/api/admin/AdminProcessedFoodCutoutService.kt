package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.CutoutReviewStatus
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.port.storage.PresignedUploadPort
import com.kbap.common.port.storage.StorageObjectStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Service
class AdminProcessedFoodCutoutService(
    private val products: ProcessedFoodProductJpaRepository,
    private val uploads: PresignedUploadPort,
    private val store: StorageObjectStore,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.storage.public-base-url:}") private val publicBaseUrl: String,
    @Value("\${kbap.storage.key-prefix:}") private val keyPrefix: String,
) {
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTransaction = TransactionTemplate(transactionManager)

    fun presign(productId: Long, contentLength: Long): AdminProcessedFoodCutoutPresignResponse {
        if (contentLength > MAX_BYTES) throw BusinessException(ErrorCode.IMAGE_TOO_LARGE)
        val key = readTransaction.execute { keyOf(product(productId)) }!!
        val upload = uploads.issue(key, CONTENT_TYPE, contentLength, UPLOAD_TTL)
        return AdminProcessedFoodCutoutPresignResponse(upload.uploadUrl, upload.requiredHeaders, upload.publicUrl, upload.objectKey, upload.expiresAt)
    }

    fun confirm(productId: Long, key: String, adminId: Long): AdminProcessedFoodCutoutResponse {
        if (key != readTransaction.execute { keyOf(product(productId)) }) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val url = uploadedUrlOrNull(key) ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_CUTOUT_NOT_UPLOADED)
        return writeTransaction.execute {
            product(productId).apply { saveApprovedCutout(url, adminId, LocalDateTime.now()) }.let(::responseOf)
        }!!
    }

    fun deleteCutout(productId: Long): AdminProcessedFoodCutoutResponse =
        writeTransaction.execute { product(productId).apply { clearCutout() }.let(::responseOf) }!!

    fun bulkRegister(items: List<AdminCutoutRegisterItem>): AdminCutoutBulkRegisterResponse {
        if (items.isEmpty() || items.size > MAX_BULK_REGISTER) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val failures = mutableListOf<AdminCutoutRegisterFailure>()
        val duplicated = items.groupingBy { it.productId }.eachCount().filterValues { it > 1 }.keys
        val known = readTransaction.execute { products.findAllById(items.mapNotNull { it.productId }.distinct()).associate { it.id to keyOf(it) } }!!

        val candidates = items.mapNotNull { item ->
            val reason = when {
                item.productId in duplicated -> "DUPLICATE"
                item.productId == null || item.productId !in known -> "PRODUCT_NOT_FOUND"
                item.key != known[item.productId] -> "KEY_MISMATCH"
                item.route !in CUTOUT_ROUTES -> "INVALID_ROUTE"
                item.coverage != null && (item.coverage < BigDecimal.ZERO || item.coverage > BigDecimal.ONE) -> "INVALID_COVERAGE"
                else -> null
            }
            if (reason != null) failures += AdminCutoutRegisterFailure(item.productId, reason)
            item.takeIf { reason == null }
        }

        val verified = Executors.newFixedThreadPool(HEAD_PARALLELISM).use { pool ->
            candidates.map { item -> pool.submit(Callable { item to uploadedUrlOrNull(item.key!!) }) }.map { it.get() }
        }
        val registrable = verified.mapNotNull { (item, url) ->
            if (url == null) failures += AdminCutoutRegisterFailure(item.productId, "NOT_UPLOADED")
            url?.let { item to it }
        }

        writeTransaction.executeWithoutResult {
            val loaded = products.findAllById(registrable.map { it.first.productId!! }).associateBy { it.id }
            registrable.forEach { (item, url) ->
                loaded[item.productId]?.registerCutout(url, item.route!!, item.coverage?.setScale(COVERAGE_SCALE, RoundingMode.HALF_UP))
            }
        }
        return AdminCutoutBulkRegisterResponse(registrable.size, failures)
    }

    fun reviewCutout(productId: Long, approved: Boolean, reason: String?, adminId: Long): AdminCutoutReviewItem =
        writeTransaction.execute {
            val product = product(productId)
            if (product.cutoutImageUrl == null) throw BusinessException(ErrorCode.PROCESSED_FOOD_CUTOUT_NOT_FOUND)
            product.reviewCutout(approved, reason, adminId, LocalDateTime.now())
            AdminCutoutReviewItem.from(product)
        }!!

    fun getCutoutReviewPage(
        status: CutoutReviewStatus?,
        route: String?,
        minCoverage: BigDecimal?,
        maxCoverage: BigDecimal?,
        cursor: Long?,
        size: Int,
    ): AdminCutoutReviewPage {
        val pageSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val rows = readTransaction.execute {
            products.findCutoutReviewPage(status, route, minCoverage, maxCoverage, cursor, PageRequest.of(0, pageSize + 1)).map(AdminCutoutReviewItem::from)
        }!!
        val items = rows.take(pageSize)
        return AdminCutoutReviewPage(items, items.lastOrNull()?.processedFoodId?.takeIf { rows.size > pageSize })
    }

    fun bulkApprove(minCoverage: BigDecimal, dryRun: Boolean, adminId: Long): AdminCutoutBulkApproveResponse {
        if (minCoverage < BigDecimal.ZERO || minCoverage > BigDecimal.ONE) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val count = if (dryRun) {
            readTransaction.execute { products.countCutoutsToApprove(CutoutReviewStatus.PENDING, ROUTE_VISION, minCoverage) }!!
        } else {
            writeTransaction.execute {
                products.approveCutouts(CutoutReviewStatus.PENDING, CutoutReviewStatus.APPROVED, ROUTE_VISION, minCoverage, adminId, LocalDateTime.now()).toLong()
            }!!
        }
        return AdminCutoutBulkApproveResponse(count, dryRun)
    }

    private fun uploadedUrlOrNull(key: String): String? =
        runCatching { store.head(key) }.getOrNull()
            ?.takeIf { it.contentType == CONTENT_TYPE && it.sizeBytes in 1..MAX_BYTES }
            ?.let { "${publicBaseUrl.trimEnd('/')}/$key?v=${it.lastModified.epochSecond}" }

    private fun product(id: Long): ProcessedFoodProduct =
        products.findById(id).orElseThrow { BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND) }

    private fun keyOf(product: ProcessedFoodProduct): String =
        listOf(keyPrefix.trim('/'), "processed/cutout/${product.reportNo}.png").filter { it.isNotEmpty() }.joinToString("/")

    private fun responseOf(product: ProcessedFoodProduct) =
        AdminProcessedFoodCutoutResponse(product.id, product.cutoutImageUrl, product.displayImageUrl())

    companion object {
        const val CONTENT_TYPE = "image/png"
        const val MAX_BYTES = 2L * 1024 * 1024
        const val MAX_BULK_REGISTER = 500
        const val MAX_PAGE_SIZE = 200
        const val ROUTE_VISION = "vision"
        val CUTOUT_ROUTES = setOf(ROUTE_VISION, "fallback")
        private const val HEAD_PARALLELISM = 8
        private const val COVERAGE_SCALE = 4
        private val UPLOAD_TTL = Duration.ofMinutes(10)
    }
}
