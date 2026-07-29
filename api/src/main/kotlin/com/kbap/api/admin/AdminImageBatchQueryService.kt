package com.kbap.api.admin

import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.food.model.ImageBatchStatus
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminImageBatchQueryService(
    private val batchRepository: ImageBatchJpaRepository,
    private val itemRepository: ImageBatchItemJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getRecentBatches(): List<AdminImageBatchView> {
        val batches = batchRepository
            .findAll(PageRequest.of(0, RECENT_SIZE, Sort.by(Sort.Direction.DESC, "id")))
            .content
        if (batches.isEmpty()) return emptyList()

        val countsByBatch = itemRepository.countGroupByBatchIdAndStatus(batches.map { it.id })
            .groupBy { it.batchId }
        return batches.map { batch ->
            val counts = countsByBatch[batch.id].orEmpty().associate { it.itemStatus to it.count }
            AdminImageBatchView.from(batch, counts)
        }
    }

    companion object {
        const val RECENT_SIZE = 20
    }
}

data class AdminImageBatchView(
    val id: Long,
    val batchStatus: ImageBatchStatus,
    val model: String,
    val promptVersion: String,
    val submittedAt: LocalDateTime,
    val collectedAt: LocalDateTime?,
    val pendingCount: Long,
    val doneCount: Long,
    val failedCount: Long,
) {
    val totalCount: Long get() = pendingCount + doneCount + failedCount

    companion object {
        fun from(batch: ImageBatch, counts: Map<ImageBatchItemStatus, Long>): AdminImageBatchView =
            AdminImageBatchView(
                id = batch.id,
                batchStatus = batch.batchStatus,
                model = batch.model,
                promptVersion = batch.promptVersion,
                submittedAt = batch.submittedAt,
                collectedAt = batch.collectedAt,
                pendingCount = counts[ImageBatchItemStatus.PENDING] ?: 0L,
                doneCount = counts[ImageBatchItemStatus.DONE] ?: 0L,
                failedCount = counts[ImageBatchItemStatus.FAILED] ?: 0L,
            )
    }
}
