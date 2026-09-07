package com.kbap.api.admin

import com.kbap.common.domain.food.model.ImageBatchStatus
import java.time.LocalDateTime

data class AdminImageBatchListResponse(
    val batches: List<AdminImageBatchItemResponse>,
) {
    companion object {
        fun from(views: List<AdminImageBatchView>): AdminImageBatchListResponse =
            AdminImageBatchListResponse(batches = views.map(AdminImageBatchItemResponse::from))
    }
}

data class AdminImageBatchItemResponse(
    val id: Long,
    val batchStatus: ImageBatchStatus,
    val model: String,
    val promptVersion: String,
    val submittedAt: LocalDateTime,
    val collectedAt: LocalDateTime?,
    val pendingCount: Long,
    val doneCount: Long,
    val failedCount: Long,
    val totalCount: Long,
) {
    companion object {
        fun from(view: AdminImageBatchView): AdminImageBatchItemResponse =
            AdminImageBatchItemResponse(
                id = view.id,
                batchStatus = view.batchStatus,
                model = view.model,
                promptVersion = view.promptVersion,
                submittedAt = view.submittedAt,
                collectedAt = view.collectedAt,
                pendingCount = view.pendingCount,
                doneCount = view.doneCount,
                failedCount = view.failedCount,
                totalCount = view.totalCount,
            )
    }
}
