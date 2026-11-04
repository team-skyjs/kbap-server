package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.processedfood.ProcessedFoodImageJpaRepository
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodImage
import com.kbap.common.domain.processedfood.model.ProcessedFoodImageKind
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.port.storage.PresignedUploadPort
import com.kbap.common.port.storage.StorageObjectStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.Base64

@Service
class AdminProcessedFoodImageService(
    private val products: ProcessedFoodProductJpaRepository,
    private val images: ProcessedFoodImageJpaRepository,
    private val uploads: PresignedUploadPort,
    private val store: StorageObjectStore,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.storage.public-base-url:}") private val publicBaseUrl: String,
    @Value("\${kbap.storage.key-prefix:}") private val keyPrefix: String,
) {
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTransaction = TransactionTemplate(transactionManager)

    fun getImages(productId: Long): AdminProcessedFoodImagesResponse =
        readTransaction.execute { product(productId); responseOf(productId) }!!

    fun presign(productId: Long, contentType: String, contentLength: Long, contentSha256: String): AdminProcessedFoodCutoutPresignResponse {
        val extension = EXTENSIONS[contentType] ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        if (contentLength > MAX_BYTES) throw BusinessException(ErrorCode.IMAGE_TOO_LARGE)
        val sha256 = contentSha256.lowercase().takeIf { SHA256_HEX.matches(it) } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val key = readTransaction.execute { keyBaseOf(product(productId)) }!! + sha256.take(KEY_HASH_LENGTH) + "." + extension
        val checksum = Base64.getEncoder().encodeToString(sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val upload = uploads.issue(key, contentType, contentLength, UPLOAD_TTL, checksum)
        return AdminProcessedFoodCutoutPresignResponse(upload.uploadUrl, upload.requiredHeaders, upload.publicUrl, upload.objectKey, upload.expiresAt)
    }

    fun confirm(productId: Long, key: String, kind: ProcessedFoodImageKind): AdminProcessedFoodImagesResponse {
        if (kind == ProcessedFoodImageKind.CUTOUT) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val keyBase = readTransaction.execute { keyBaseOf(product(productId)) }!!
        val extension = key.takeIf { it.startsWith(keyBase) }?.removePrefix(keyBase)?.let(KEY_HASH_PART::matchEntire)?.groupValues?.get(2)
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val head = runCatching { store.head(key) }.getOrNull()
            ?.takeIf { EXTENSIONS[it.contentType] == extension && it.sizeBytes in 1..MAX_BYTES }
            ?.takeIf { it.sha256Hex?.take(KEY_HASH_LENGTH) == key.removePrefix(keyBase).take(KEY_HASH_LENGTH) }
        if (head == null) throw BusinessException(ErrorCode.PROCESSED_FOOD_IMAGE_NOT_UPLOADED)
        val url = "${publicBaseUrl.trimEnd('/')}/$key"
        return writeTransaction.execute {
            products.findForUpdateById(productId) ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)
            val current = images.findAllByProcessedFoodIdOrderBySortOrderAscIdAsc(productId)
            if (current.none { it.imageUrl == url }) {
                images.save(ProcessedFoodImage(productId, url, kind, (current.maxOfOrNull { it.sortOrder } ?: -1) + 1))
            }
            responseOf(productId)
        }!!
    }

    fun reorder(productId: Long, imageIds: List<Long>): AdminProcessedFoodImagesResponse =
        writeTransaction.execute {
            products.findForUpdateById(productId) ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)
            val current = images.findAllByProcessedFoodIdOrderBySortOrderAscIdAsc(productId).associateBy { it.id }
            if (imageIds.size != current.size || imageIds.toSet() != current.keys) throw BusinessException(ErrorCode.INVALID_REQUEST)
            imageIds.forEachIndexed { index, id -> current.getValue(id).sortOrder = index }
            responseOf(productId)
        }!!

    fun deleteImage(productId: Long, imageId: Long): AdminProcessedFoodImagesResponse =
        writeTransaction.execute {
            val image = images.findById(imageId).orElse(null)?.takeIf { it.processedFoodId == productId }
                ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_IMAGE_NOT_FOUND)
            image.delete()
            responseOf(productId)
        }!!

    private fun responseOf(productId: Long) =
        AdminProcessedFoodImagesResponse(productId, images.findAllByProcessedFoodIdOrderBySortOrderAscIdAsc(productId).map(AdminProcessedFoodImageItem::from))

    private fun product(id: Long): ProcessedFoodProduct =
        products.findById(id).orElseThrow { BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND) }

    private fun keyBaseOf(product: ProcessedFoodProduct): String =
        listOf(keyPrefix.trim('/'), "processed/images/${product.reportNo}-").filter { it.isNotEmpty() }.joinToString("/")

    companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        private val EXTENSIONS = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp")
        private val UPLOAD_TTL = Duration.ofMinutes(10)
        private const val KEY_HASH_LENGTH = 16
        private val KEY_HASH_PART = Regex("([0-9a-f]{16})\\.(png|jpg|webp)")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
