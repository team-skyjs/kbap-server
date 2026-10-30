package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.dto.CutoutReviewCount
import com.kbap.common.domain.processedfood.model.CutoutReviewStatus
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.port.storage.PresignedUploadPort
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.util.LikeWildcards
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
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

    fun presign(productId: Long, contentLength: Long, contentSha256: String): AdminProcessedFoodCutoutPresignResponse {
        if (contentLength > MAX_BYTES) throw BusinessException(ErrorCode.IMAGE_TOO_LARGE)
        val sha256 = contentSha256.lowercase().takeIf { SHA256_HEX.matches(it) } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val key = readTransaction.execute { keyBaseOf(product(productId)) }!! + sha256.take(KEY_HASH_LENGTH) + KEY_SUFFIX
        val checksum = Base64.getEncoder().encodeToString(sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val upload = uploads.issue(key, CONTENT_TYPE, contentLength, UPLOAD_TTL, checksum)
        return AdminProcessedFoodCutoutPresignResponse(upload.uploadUrl, upload.requiredHeaders, upload.publicUrl, upload.objectKey, upload.expiresAt)
    }

    fun confirm(productId: Long, key: String, adminId: Long): AdminProcessedFoodCutoutResponse {
        if (!isKeyOf(readTransaction.execute { keyBaseOf(product(productId)) }!!, key)) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val url = verify(key).first ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_CUTOUT_NOT_UPLOADED)
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
        val known = readTransaction.execute { products.findAllById(items.mapNotNull { it.productId }.distinct()).associate { it.id to keyBaseOf(it) } }!!

        val candidates = items.mapNotNull { item ->
            val reason = when {
                item.productId in duplicated -> "DUPLICATE"
                item.productId == null || item.productId !in known -> "PRODUCT_NOT_FOUND"
                !isKeyOf(known.getValue(item.productId), item.key) -> "KEY_MISMATCH"
                item.route !in CUTOUT_ROUTES -> "INVALID_ROUTE"
                item.coverage != null && (item.coverage < BigDecimal.ZERO || item.coverage > BigDecimal.ONE) -> "INVALID_COVERAGE"
                else -> null
            }
            if (reason != null) failures += AdminCutoutRegisterFailure(item.productId, reason)
            item.takeIf { reason == null }
        }

        val verified = Executors.newFixedThreadPool(HEAD_PARALLELISM).use { pool ->
            candidates.map { item -> pool.submit(Callable { item to verify(item.key!!) }) }.map { it.get() }
        }
        val registrable = verified.mapNotNull { (item, result) ->
            val (url, reason) = result
            if (url == null) failures += AdminCutoutRegisterFailure(item.productId, reason ?: "NOT_UPLOADED")
            url?.let { item to it }
        }

        writeTransaction.executeWithoutResult {
            val loaded = products.findAllForUpdateByIdIn(registrable.map { it.first.productId!! }).associateBy { it.id }
            registrable.forEach { (item, url) ->
                loaded[item.productId]?.registerCutout(url, item.route!!, item.coverage?.setScale(COVERAGE_SCALE, RoundingMode.HALF_UP))
            }
        }
        return AdminCutoutBulkRegisterResponse(registrable.size, failures)
    }

    fun reviewCutout(productId: Long, approved: Boolean, reason: String?, adminId: Long, expectedCutoutImageUrl: String? = null): AdminCutoutReviewItem =
        writeTransaction.execute {
            val product = products.findForUpdateById(productId) ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)
            if (product.cutoutImageUrl == null) throw BusinessException(ErrorCode.PROCESSED_FOOD_CUTOUT_NOT_FOUND)
            if (expectedCutoutImageUrl != null && expectedCutoutImageUrl != product.cutoutImageUrl) throw BusinessException(ErrorCode.PROCESSED_FOOD_CUTOUT_CHANGED)
            product.reviewCutout(approved, reason, adminId, LocalDateTime.now())
            AdminCutoutReviewItem.from(product)
        }!!

    fun getCutoutReviewPage(
        status: CutoutReviewStatus?,
        route: String?,
        minCoverage: BigDecimal?,
        maxCoverage: BigDecimal?,
        cursor: String?,
        size: Int,
        sort: AdminCutoutReviewSort? = null,
        q: String? = null,
    ): AdminCutoutReviewPage {
        val pageSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val page = PageRequest.of(0, pageSize + 1)
        val keyword = q?.trim()?.takeIf { it.isNotEmpty() }
        val namePattern = keyword?.let { "%" + LikeWildcards.escape(it.lowercase()) + "%" }
        val exactKey = keyword?.takeIf { it.all(Char::isDigit) }
        val exactId = exactKey?.toLongOrNull()
        val recentStatus = status.takeIf { sort == AdminCutoutReviewSort.RECENT && it != null && it != CutoutReviewStatus.PENDING }
        if (recentStatus != null) {
            val after = cursor?.let(::decodeRecentCursor)
            val rows = readTransaction.execute {
                products.findRecentlyReviewedCutoutPage(recentStatus, route, minCoverage, maxCoverage, namePattern, exactKey, exactId, after?.first, after?.second, page)
                    .map(AdminCutoutReviewItem::from)
            }!!
            val items = rows.take(pageSize)
            return AdminCutoutReviewPage(items, items.lastOrNull()?.takeIf { rows.size > pageSize }?.let(::encodeRecentCursor))
        }
        val after = cursor?.let { it.toLongOrNull() ?: throw BusinessException(ErrorCode.INVALID_CURSOR) }
        val rows = readTransaction.execute {
            products.findCutoutReviewPage(status, route, minCoverage, maxCoverage, namePattern, exactKey, exactId, after, page).map(AdminCutoutReviewItem::from)
        }!!
        val items = rows.take(pageSize)
        return AdminCutoutReviewPage(items, items.lastOrNull()?.processedFoodId?.takeIf { rows.size > pageSize }?.toString())
    }

    private fun encodeRecentCursor(item: AdminCutoutReviewItem): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${item.reviewedAt}|${item.processedFoodId}".toByteArray())

    private fun decodeRecentCursor(cursor: String): Pair<LocalDateTime, Long> =
        runCatching {
            val (at, id) = String(Base64.getUrlDecoder().decode(cursor)).split('|', limit = 2)
            LocalDateTime.parse(at) to id.toLong()
        }.getOrElse { throw BusinessException(ErrorCode.INVALID_CURSOR) }

    fun getCutoutReviewSummary(): AdminCutoutReviewSummary {
        val today = LocalDate.now(SEOUL)
        val since = today.atStartOfDay(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        val until = today.plusDays(1).atStartOfDay(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        val counts = readTransaction.execute { products.countCutoutReviews(since, until) }!!
        fun tally(rows: List<CutoutReviewCount>): AdminCutoutReviewTally {
            fun of(status: CutoutReviewStatus) = rows.filter { it.reviewStatus == status }
            return AdminCutoutReviewTally(
                pending = of(CutoutReviewStatus.PENDING).sumOf { it.total },
                approved = of(CutoutReviewStatus.APPROVED).sumOf { it.total },
                rejected = of(CutoutReviewStatus.REJECTED).sumOf { it.total },
                todayApproved = of(CutoutReviewStatus.APPROVED).sumOf { it.reviewedInRange },
                todayRejected = of(CutoutReviewStatus.REJECTED).sumOf { it.reviewedInRange },
            )
        }
        val total = tally(counts)
        return AdminCutoutReviewSummary(
            date = today,
            pending = total.pending,
            approved = total.approved,
            rejected = total.rejected,
            todayApproved = total.todayApproved,
            todayRejected = total.todayRejected,
            byRoute = CUTOUT_ROUTES.associateWith { route -> tally(counts.filter { it.route == route }) },
        )
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

    private fun verify(key: String): Pair<String?, String?> {
        val head = runCatching { store.head(key) }.getOrNull()
            ?.takeIf { it.contentType == CONTENT_TYPE && it.sizeBytes in 1..MAX_BYTES }
            ?: return null to "NOT_UPLOADED"
        val sha256 = head.sha256Hex ?: return null to "CHECKSUM_MISSING"
        if (sha256.take(KEY_HASH_LENGTH) != key.removeSuffix(KEY_SUFFIX).takeLast(KEY_HASH_LENGTH)) return null to "CHECKSUM_MISMATCH"
        return "${publicBaseUrl.trimEnd('/')}/$key" to null
    }

    private fun product(id: Long): ProcessedFoodProduct =
        products.findById(id).orElseThrow { BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND) }

    private fun keyBaseOf(product: ProcessedFoodProduct): String =
        listOf(keyPrefix.trim('/'), "processed/cutout/${product.reportNo}-").filter { it.isNotEmpty() }.joinToString("/")

    private fun isKeyOf(keyBase: String, key: String?): Boolean =
        key != null && key.startsWith(keyBase) && KEY_HASH_PART.matches(key.removePrefix(keyBase))

    private fun responseOf(product: ProcessedFoodProduct) =
        AdminProcessedFoodCutoutResponse(product.id, product.cutoutImageUrl, product.displayImageUrl())

    companion object {
        const val CONTENT_TYPE = "image/png"
        const val MAX_BYTES = 2L * 1024 * 1024
        const val MAX_BULK_REGISTER = 500
        const val MAX_PAGE_SIZE = 200
        const val ROUTE_VISION = "vision"
        val CUTOUT_ROUTES = setOf(ROUTE_VISION, "fallback")
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private const val HEAD_PARALLELISM = 8
        private const val COVERAGE_SCALE = 4
        private val UPLOAD_TTL = Duration.ofMinutes(10)
        private const val KEY_HASH_LENGTH = 16
        private const val KEY_SUFFIX = ".png"
        private val KEY_HASH_PART = Regex("[0-9a-f]{16}\\.png")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
