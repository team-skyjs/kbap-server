package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.port.storage.PresignedUploadPort
import com.kbap.common.port.storage.StorageObjectStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

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

    fun confirm(productId: Long, key: String): AdminProcessedFoodCutoutResponse {
        if (key != readTransaction.execute { keyOf(product(productId)) }) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val uploaded = runCatching { store.head(key) }.getOrNull()
            ?.takeIf { it.contentType == CONTENT_TYPE && it.sizeBytes in 1..MAX_BYTES }
            ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_CUTOUT_NOT_UPLOADED)
        val url = "${publicBaseUrl.trimEnd('/')}/$key?v=${uploaded.lastModified.epochSecond}"
        return writeTransaction.execute { product(productId).apply { cutoutImageUrl = url }.let(::responseOf) }!!
    }

    fun deleteCutout(productId: Long): AdminProcessedFoodCutoutResponse =
        writeTransaction.execute { product(productId).apply { cutoutImageUrl = null }.let(::responseOf) }!!

    private fun product(id: Long): ProcessedFoodProduct =
        products.findById(id).orElseThrow { BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND) }

    private fun keyOf(product: ProcessedFoodProduct): String =
        listOf(keyPrefix.trim('/'), "processed/cutout/${product.reportNo}.png").filter { it.isNotEmpty() }.joinToString("/")

    private fun responseOf(product: ProcessedFoodProduct) =
        AdminProcessedFoodCutoutResponse(product.id, product.cutoutImageUrl, product.displayImageUrl())

    companion object {
        const val CONTENT_TYPE = "image/png"
        const val MAX_BYTES = 2L * 1024 * 1024
        private val UPLOAD_TTL = Duration.ofMinutes(10)
    }
}
