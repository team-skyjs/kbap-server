package com.kbap.api.admin

import com.kbap.api.food.FoodImageBatchCollectService
import com.kbap.api.food.FoodImageBatchSubmitService
import com.kbap.api.image.ImageUploadInput
import com.kbap.api.image.PresignedUploadService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.port.storage.StorageObjectStore
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@Service
class AdminFoodPipelineService(
    private val foodRepository: FoodJpaRepository,
    private val contentOutboxRepository: FoodContentOutboxJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
    private val imageBatchRepository: ImageBatchJpaRepository,
    private val imageBatchItemRepository: ImageBatchItemJpaRepository,
    private val submitService: FoodImageBatchSubmitService,
    private val collectService: FoodImageBatchCollectService,
    private val presignedUploadService: PresignedUploadService,
    private val storageObjectStore: StorageObjectStore,
    private val adminFoodService: AdminFoodService,
    private val adminFoodDashboardService: AdminFoodDashboardService,
    private val adminFoodCommandService: AdminFoodCommandService,
    private val auditRecorder: AdminAuditRecorder,
    private val lockProvider: LockProvider,
    transactionManager: PlatformTransactionManager,
) {
    private val auditTransaction = TransactionTemplate(transactionManager)
    private val itemTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Transactional
    fun recollectOne(adminId: Long, foodId: Long): AdminRecollectOneResponse {
        val food = getFood(foodId)
        contentOutboxRepository.findByFoodIdInAndOutboxStatus(listOf(foodId), FoodContentOutboxStatus.PENDING).firstOrNull()?.let {
            return AdminRecollectOneResponse(outboxId = it.id, created = false)
        }
        val outbox = contentOutboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
        auditRecorder.record(adminId, AdminAuditAction.FOOD_RECOLLECT, AdminAuditTargetType.FOOD, food.id, null, mapOf("outboxId" to outbox.id))
        return AdminRecollectOneResponse(outboxId = outbox.id, created = true)
    }

    @Transactional
    fun recollect(adminId: Long, query: String?, status: FoodContentStatus?): AdminFoodRecollectResult {
        val result = adminFoodService.requestRecollect(query, status)
        if (result.created > 0) {
            auditRecorder.record(
                adminId, AdminAuditAction.FOOD_RECOLLECT, AdminAuditTargetType.FOOD, null, null,
                mapOf("created" to result.created, "skipped" to result.skipped), note = "q=$query status=$status",
            )
        }
        return result
    }

    fun regenerateImage(adminId: Long, foodId: Long): AdminImageRegenerateResponse {
        val food = getFood(foodId)
        val result = submitService.submitForFoods(listOf(food.id))
        val item = imageBatchItemRepository.findTop10ByFoodIdOrderByIdDesc(food.id).first()
        audit(adminId, AdminAuditAction.FOOD_IMAGE_REGENERATE, AdminAuditTargetType.FOOD, food.id, null, mapOf("batchId" to item.batchId))
        return AdminImageRegenerateResponse(batchId = result.batchIds.first(), itemId = item.id)
    }

    fun issueImageUploadUrl(adminId: Long, foodId: Long, contentType: String, contentLength: Long): AdminImageUploadUrlResponse {
        getFood(foodId)
        val upload = presignedUploadService.issueUploadUrl(
            ImageUploadInput(memberId = adminId, purpose = UploadPurpose.FOOD.name, contentType = contentType, contentLength = contentLength),
        )
        return AdminImageUploadUrlResponse(upload.uploadUrl, upload.requiredHeaders, upload.objectKey, upload.expiresAt)
    }

    @Transactional
    fun replaceImage(adminId: Long, foodId: Long, objectKey: String): AdminFoodTransitionResponse {
        val food = getFood(foodId)
        val meta = storageObjectStore.head(objectKey) ?: throw BusinessException(ErrorCode.UPLOADED_OBJECT_NOT_FOUND)
        if (!meta.contentType.startsWith("image/")) throw BusinessException(ErrorCode.NOT_IMAGE_FILE)
        val before = food.imageRef
        food.replaceImage(objectKey)
        if (food.isReady()) vectorOutboxRepository.enqueueIfAbsent(food.id, FoodVectorOutboxOperation.UPSERT)
        auditRecorder.record(
            adminId, AdminAuditAction.FOOD_IMAGE_REPLACE, AdminAuditTargetType.FOOD, food.id,
            mapOf("imageRef" to before), mapOf("imageRef" to objectKey),
        )
        return AdminFoodTransitionResponse.from(food)
    }

    @Transactional(readOnly = true)
    fun getImageBatchPage(page: Int, size: Int): AdminImageBatchPageResponse {
        val result = imageBatchRepository.findAll(PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")))
        return AdminImageBatchPageResponse(
            items = withCounts(result.content),
            page = page,
            size = size,
            totalCount = result.totalElements,
            totalPages = totalPagesOf(result.totalElements, size),
        )
    }

    fun countImageCandidates(): AdminImageCandidateCountResponse = AdminImageCandidateCountResponse(submitService.countCandidates())

    @Transactional(readOnly = true)
    fun getImageBatchDetail(batchId: Long): AdminImageBatchDetailResponse {
        val batch = imageBatchRepository.findById(batchId).orElseThrow { BusinessException(ErrorCode.INVALID_REQUEST) }
        val items = imageBatchItemRepository.findByBatchIdOrderByIdAsc(batchId)
        val names = foodRepository.findAllById(items.map { it.foodId }.toSet()).associate { it.id to it.displayName }
        return AdminImageBatchDetailResponse(
            batch = withCounts(listOf(batch)).single(),
            items = items.map {
                AdminImageBatchItemResponse(
                    itemId = it.id,
                    foodId = it.foodId,
                    displayName = names[it.foodId] ?: "삭제된 음식(#${it.foodId})",
                    status = it.itemStatus,
                    fileName = it.fileName,
                    errorMsg = it.errorMsg,
                )
            },
        )
    }

    fun collectImagesNow(adminId: Long): AdminImageCollectResponse {
        val lock = lockProvider.lock(
            LockConfiguration(Instant.now(), FoodImageBatchCollectService.LOCK_NAME, Duration.ofMinutes(30), Duration.ZERO),
        ).orElseThrow { BusinessException(ErrorCode.IMAGE_COLLECT_IN_PROGRESS) }
        val summary = try {
            collectService.collectSubmitted()
        } finally {
            lock.unlock()
        }
        audit(
            adminId, AdminAuditAction.IMAGE_COLLECT, AdminAuditTargetType.IMAGE_BATCH, null, null,
            mapOf("collectedBatches" to summary.collectedBatches, "doneItems" to summary.doneItems, "failedItems" to summary.failedItems),
        )
        return AdminImageCollectResponse(summary.collectedBatches, summary.doneItems, summary.failedItems)
    }

    fun resubmitItems(adminId: Long, itemIds: List<Long>): AdminImageResubmitResponse {
        val foodIds = imageBatchItemRepository.findAllById(itemIds).map { it.foodId }.distinct()
        if (foodIds.isEmpty()) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val result = submitService.submitForFoods(foodIds)
        audit(adminId, AdminAuditAction.IMAGE_RESUBMIT, AdminAuditTargetType.IMAGE_BATCH, null, null, mapOf("itemIds" to itemIds, "batchIds" to result.batchIds))
        return AdminImageResubmitResponse(batchIds = result.batchIds, itemCount = result.submittedFoodCount)
    }

    @Transactional(readOnly = true)
    fun getContentOutboxPage(status: FoodContentOutboxStatus?, foodId: Long?, stuckHours: Int, page: Int, size: Int): AdminContentOutboxPageResponse {
        val result = contentOutboxRepository.findPage(status, foodId, PageRequest.of(page - 1, size))
        val threshold = LocalDateTime.now().minusHours(stuckHours.toLong())
        return AdminContentOutboxPageResponse(
            items = result.content.map {
                AdminContentOutboxResponse(
                    id = it.id, foodId = it.foodId, displayName = it.displayName, status = it.outboxStatus, attempts = it.attempts,
                    createdAt = it.createdAt, sentAt = it.sentAt, lastError = it.lastError, lastFailedAt = it.lastFailedAt,
                    stuck = it.isStuck(threshold),
                )
            },
            page = page,
            size = size,
            totalCount = result.totalElements,
            totalPages = totalPagesOf(result.totalElements, size),
            stuckHours = stuckHours,
        )
    }

    @Transactional
    fun requeueContentOutbox(adminId: Long, outboxId: Long): AdminContentOutboxResponse =
        mutateContentOutbox(adminId, outboxId, AdminAuditAction.CONTENT_OUTBOX_REQUEUE) { it.requeue() }

    @Transactional
    fun cancelContentOutbox(adminId: Long, outboxId: Long): AdminContentOutboxResponse =
        mutateContentOutbox(adminId, outboxId, AdminAuditAction.CONTENT_OUTBOX_CANCEL) { it.cancel() }

    @Transactional(readOnly = true)
    fun getVectorOutboxPage(status: FoodVectorOutboxStatus?, page: Int, size: Int): AdminVectorOutboxPageResponse {
        val pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"))
        val result = if (status == null) vectorOutboxRepository.findAll(pageable) else vectorOutboxRepository.findByOutboxStatus(status, pageable)
        val names = foodRepository.findAllById(result.content.map { it.foodId }.toSet()).associate { it.id to it.displayName }
        return AdminVectorOutboxPageResponse(
            items = result.content.map { toVectorResponse(it, names[it.foodId]) },
            page = page,
            size = size,
            totalCount = result.totalElements,
            totalPages = totalPagesOf(result.totalElements, size),
        )
    }

    @Transactional
    fun enqueueVectors(adminId: Long): AdminVectorEnqueueResponse {
        val before = foodRepository.countReadyWithoutVectorUpsertOutbox()
        adminFoodDashboardService.enqueueReadyFoodsForVectorSync()
        val remaining = foodRepository.countReadyWithoutVectorUpsertOutbox()
        val enqueued = before - remaining
        auditRecorder.record(adminId, AdminAuditAction.VECTOR_ENQUEUE, AdminAuditTargetType.VECTOR_OUTBOX, null, null, mapOf("enqueued" to enqueued, "remaining" to remaining))
        return AdminVectorEnqueueResponse(enqueued = enqueued, remaining = remaining)
    }

    @Transactional
    fun retryVector(adminId: Long, outboxId: Long): AdminVectorOutboxResponse {
        val outbox = vectorOutboxRepository.findById(outboxId).orElseThrow { BusinessException(ErrorCode.INVALID_REQUEST) }
        if (outbox.outboxStatus != FoodVectorOutboxStatus.FAILED) throw BusinessException(ErrorCode.FOOD_CONTENT_REQUEST_NOT_PENDING)
        outbox.retry()
        auditRecorder.record(adminId, AdminAuditAction.VECTOR_RETRY, AdminAuditTargetType.VECTOR_OUTBOX, outbox.id, null, null)
        return toVectorResponse(outbox, foodRepository.findById(outbox.foodId).orElse(null)?.displayName)
    }

    @Transactional
    fun retryAllFailedVectors(adminId: Long): AdminVectorRetryAllResponse {
        val retried = vectorOutboxRepository.retryAllFailed()
        auditRecorder.record(adminId, AdminAuditAction.VECTOR_RETRY, AdminAuditTargetType.VECTOR_OUTBOX, null, null, mapOf("retried" to retried), note = "retry-all-failed")
        return AdminVectorRetryAllResponse(retried)
    }

    fun bulk(adminId: Long, action: AdminFoodBulkAction, ids: List<Long>): AdminFoodBulkResponse {
        if (ids.size > BULK_MAX) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val results = ids.distinct().map { id ->
            try {
                itemTransaction.executeWithoutResult {
                    when (action) {
                        AdminFoodBulkAction.APPROVE -> adminFoodCommandService.approve(adminId, id)
                        AdminFoodBulkAction.RECOLLECT -> recollectOne(adminId, id)
                        AdminFoodBulkAction.DELETE -> adminFoodCommandService.deleteFood(adminId, id)
                    }
                }
                AdminFoodBulkItemResult(id = id, ok = true)
            } catch (e: BusinessException) {
                AdminFoodBulkItemResult(id = id, ok = false, code = e.errorCode.code, message = e.errorCode.message)
            }
        }
        audit(adminId, AdminAuditAction.FOOD_BULK, AdminAuditTargetType.FOOD, null, null, mapOf("action" to action.name, "ids" to ids), note = "ids=$ids")
        return AdminFoodBulkResponse(results, succeeded = results.count { it.ok }, failed = results.count { !it.ok })
    }

    private fun mutateContentOutbox(
        adminId: Long,
        outboxId: Long,
        action: AdminAuditAction,
        mutate: (FoodContentOutbox) -> Boolean,
    ): AdminContentOutboxResponse {
        val outbox = contentOutboxRepository.findById(outboxId).orElseThrow { BusinessException(ErrorCode.INVALID_REQUEST) }
        val before = outbox.outboxStatus
        if (!mutate(outbox)) throw BusinessException(ErrorCode.FOOD_CONTENT_REQUEST_NOT_PENDING)
        auditRecorder.record(
            adminId, action, AdminAuditTargetType.CONTENT_OUTBOX, outbox.id,
            mapOf("status" to before.name), mapOf("status" to outbox.outboxStatus.name),
        )
        return AdminContentOutboxResponse(
            id = outbox.id, foodId = outbox.foodId, displayName = outbox.displayName, status = outbox.outboxStatus, attempts = outbox.attempts,
            createdAt = outbox.createdAt, sentAt = outbox.sentAt, lastError = outbox.lastError, lastFailedAt = outbox.lastFailedAt, stuck = false,
        )
    }

    private fun withCounts(batches: List<ImageBatch>): List<AdminImageBatchResponse> {
        if (batches.isEmpty()) return emptyList()
        val counts = imageBatchItemRepository.countGroupByBatchIdAndStatus(batches.map { it.id }).groupBy { it.batchId }
        return batches.map { batch ->
            val byStatus = counts[batch.id].orEmpty().associate { it.itemStatus to it.count }
            AdminImageBatchResponse(
                id = batch.id,
                batchStatus = batch.batchStatus,
                openaiBatchId = batch.openaiBatchId,
                model = batch.model,
                promptVersion = batch.promptVersion,
                submittedAt = batch.submittedAt,
                collectedAt = batch.collectedAt,
                pendingCount = byStatus[ImageBatchItemStatus.PENDING] ?: 0L,
                doneCount = byStatus[ImageBatchItemStatus.DONE] ?: 0L,
                failedCount = byStatus[ImageBatchItemStatus.FAILED] ?: 0L,
            )
        }
    }

    private fun toVectorResponse(outbox: FoodVectorOutbox, displayName: String?) =
        AdminVectorOutboxResponse(
            id = outbox.id,
            foodId = outbox.foodId,
            displayName = displayName ?: "삭제된 음식(#${outbox.foodId})",
            operation = outbox.operation,
            status = outbox.outboxStatus,
            attempts = outbox.attempts,
            lastError = outbox.lastError,
            updatedAt = outbox.updatedAt,
        )

    private fun audit(
        adminId: Long,
        action: AdminAuditAction,
        targetType: AdminAuditTargetType,
        targetId: Long?,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
        note: String? = null,
    ) {
        auditTransaction.executeWithoutResult { auditRecorder.record(adminId, action, targetType, targetId, before, after, note) }
    }

    private fun getFood(id: Long): Food = foodRepository.findById(id).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }

    companion object {
        const val BULK_MAX = 500
    }
}
