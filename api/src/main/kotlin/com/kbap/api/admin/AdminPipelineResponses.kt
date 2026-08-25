package com.kbap.api.admin

import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.food.model.ImageBatchStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.time.LocalDateTime

data class AdminRecollectOneResponse(val outboxId: Long, val created: Boolean)

data class AdminImageRegenerateResponse(val batchId: Long, val itemId: Long)

data class AdminImageUploadUrlRequest(
    @field:NotBlank val contentType: String?,
    @field:NotNull @field:Positive val contentLength: Long?,
)

data class AdminImageUploadUrlResponse(
    val uploadUrl: String,
    val requiredHeaders: Map<String, String>,
    val objectKey: String,
    val expiresAt: Instant,
)

data class AdminImageReplaceRequest(
    @field:NotBlank val objectKey: String?,
)

data class AdminImageBatchResponse(
    val id: Long,
    val batchStatus: ImageBatchStatus,
    val openaiBatchId: String?,
    val model: String,
    val promptVersion: String,
    val submittedAt: LocalDateTime,
    val collectedAt: LocalDateTime?,
    val pendingCount: Long,
    val doneCount: Long,
    val failedCount: Long,
)

data class AdminImageBatchPageResponse(
    val items: List<AdminImageBatchResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminImageBatchItemResponse(
    val itemId: Long,
    val foodId: Long,
    val displayName: String,
    val status: ImageBatchItemStatus,
    val fileName: String?,
    val errorMsg: String?,
)

data class AdminImageBatchDetailResponse(
    val batch: AdminImageBatchResponse,
    val items: List<AdminImageBatchItemResponse>,
)

data class AdminImageCandidateCountResponse(val count: Int)

data class AdminImageCollectResponse(val collectedBatches: Int, val doneItems: Int, val failedItems: Int)

data class AdminImageResubmitRequest(
    @field:NotEmpty val itemIds: List<Long>?,
)

data class AdminImageResubmitResponse(val batchIds: List<Long>, val itemCount: Int)

data class AdminContentOutboxResponse(
    val id: Long,
    val foodId: Long,
    val displayName: String,
    val status: FoodContentOutboxStatus,
    val attempts: Int,
    val createdAt: LocalDateTime,
    val sentAt: LocalDateTime?,
    val lastError: String?,
    val lastFailedAt: LocalDateTime?,
    val stuck: Boolean,
)

data class AdminContentOutboxPageResponse(
    val items: List<AdminContentOutboxResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
    val stuckHours: Int,
)

data class AdminVectorOutboxResponse(
    val id: Long,
    val foodId: Long,
    val displayName: String,
    val operation: FoodVectorOutboxOperation,
    val status: FoodVectorOutboxStatus,
    val attempts: Int,
    val lastError: String?,
    val updatedAt: LocalDateTime,
)

data class AdminVectorOutboxPageResponse(
    val items: List<AdminVectorOutboxResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminVectorEnqueueResponse(val enqueued: Long, val remaining: Long)

data class AdminVectorRetryAllResponse(val retried: Int)

enum class AdminFoodBulkAction { APPROVE, RECOLLECT, DELETE }

data class AdminFoodBulkRequest(
    @field:NotNull val action: AdminFoodBulkAction?,
    @field:NotEmpty val ids: List<Long>?,
)

data class AdminFoodBulkItemResult(val id: Long, val ok: Boolean, val code: String? = null, val message: String? = null)

data class AdminFoodBulkResponse(val results: List<AdminFoodBulkItemResult>, val succeeded: Int, val failed: Int)

fun totalPagesOf(totalCount: Long, size: Int): Int = if (totalCount == 0L) 0 else ((totalCount - 1) / size + 1).toInt()
