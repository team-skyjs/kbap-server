package com.kbap.api.admin

import com.kbap.api.processedfood.ProcessedFoodDetailResponse
import com.kbap.api.processedfood.ProcessedFoodService
import com.kbap.api.processedfood.ProcessedFoodTranslationService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@Service
class AdminProcessedFoodManualService(
    private val products: ProcessedFoodProductJpaRepository,
    private val processedFoods: ProcessedFoodService,
    private val translations: ProcessedFoodTranslationService,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun createManualProduct(request: AdminProcessedFoodManualRequest): ProcessedFoodDetailResponse {
        repeat(MAX_KEY_ATTEMPTS) {
            val id = try {
                transaction.execute {
                    val reportNo = nextReportNo(products.findLatestManualReportNo())
                    products.saveAndFlush(ProcessedFoodProduct.collect(snapshotOf(reportNo, request), LocalDateTime.now(), ProcessedFoodProduct.SOURCE_MANUAL)).id
                }!!
            } catch (e: DataIntegrityViolationException) {
                return@repeat
            }
            translations.translateNameInBackground(id, request.productName!!.trim(), request.manufacturer)
            return processedFoods.getProcessedFoodDetail(id, null)
        }
        throw BusinessException(ErrorCode.INVALID_REQUEST)
    }

    fun updateManualProduct(id: Long, request: AdminProcessedFoodManualRequest): ProcessedFoodDetailResponse {
        transaction.executeWithoutResult {
            val product = products.findById(id).orElse(null) ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)
            if (product.source != ProcessedFoodProduct.SOURCE_MANUAL) throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_MANUAL)
            product.correctManually(snapshotOf(product.reportNo, request))
        }
        translations.translateNameInBackground(id, request.productName!!.trim(), request.manufacturer)
        return processedFoods.getProcessedFoodDetail(id, null)
    }

    private fun snapshotOf(reportNo: String, request: AdminProcessedFoodManualRequest) = ProcessedFoodSnapshot(
        reportNo = reportNo,
        productName = request.productName!!.trim(),
        productGb = null,
        foodType = request.foodType?.trim()?.ifEmpty { null },
        foodTypeState = null,
        manufacturer = request.manufacturer!!.trim(),
        seller = null,
        barcode = null,
        capacity = request.capacity?.trim()?.ifEmpty { null },
        rawMaterials = request.rawMaterials?.trim()?.ifEmpty { null },
        allergens = request.allergens?.trim()?.ifEmpty { null },
        nutrients = request.nutrients?.trim()?.ifEmpty { null },
        imageUrl = null,
        metaImageUrl = null,
    )

    companion object {
        const val MANUAL_PREFIX = "M"
        private const val MANUAL_DIGITS = 6
        private const val MAX_KEY_ATTEMPTS = 3

        fun nextReportNo(latest: String?): String {
            val next = (latest?.removePrefix(MANUAL_PREFIX)?.toIntOrNull() ?: 0) + 1
            return MANUAL_PREFIX + next.toString().padStart(MANUAL_DIGITS, '0')
        }
    }
}
