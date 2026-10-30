package com.kbap.api.admin

import com.kbap.api.food.FoodImageProperties
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.food.model.RegenerationIntent
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class RegenerationStateResolver(
    private val itemRepository: ImageBatchItemJpaRepository,
    private val batchRepository: ImageBatchJpaRepository,
) {
    @Transactional(readOnly = true)
    fun of(foodId: Long): AdminRegenerationStateResponse? =
        itemRepository.findRegenerationHistory(foodId, PageRequest.of(0, 1)).firstOrNull()?.let(::stateOf)

    @Transactional(readOnly = true)
    fun isAdditionalInProgress(foodId: Long): Boolean =
        itemRepository.existsByFoodIdAndRegenerationIntentAndItemStatus(foodId, RegenerationIntent.ADDITIONAL, ImageBatchItemStatus.PENDING)

    @Transactional(readOnly = true)
    fun generationsOf(foodId: Long, imageKeys: Collection<String>): Map<String, AdminImageGenerationResponse> {
        if (imageKeys.isEmpty()) return emptyMap()
        val items = itemRepository.findDoneByFoodIdAndFileNameIn(foodId, imageKeys).distinctBy { it.fileName }
        val batches = batchRepository.findAllById(items.map { it.batchId }.distinct()).associateBy { it.id }
        return items.mapNotNull { item ->
            val fileName = item.fileName ?: return@mapNotNull null
            batches[item.batchId]?.let { fileName to AdminImageGenerationResponse.of(item, it) }
        }.toMap()
    }

    private fun stateOf(item: ImageBatchItem): AdminRegenerationStateResponse? {
        val state = when (item.itemStatus) {
            ImageBatchItemStatus.PENDING -> AdminRegenerationStateResponse.State.IN_PROGRESS
            ImageBatchItemStatus.FAILED -> AdminRegenerationStateResponse.State.FAILED
            ImageBatchItemStatus.DONE -> return null
        }
        return AdminRegenerationStateResponse(
            state = state,
            intent = item.regenerationIntent?.name,
            reason = item.regenerationReason,
            at = item.updatedAt,
        )
    }
}

@Schema(description = "마지막 이미지 재생성 배치 항목 기준의 상태(추가 생성 ADDITIONAL 은 제외 — additionalInProgress 로 본다). 이력이 없거나 마지막이 성공(DONE)이면 null")
data class AdminRegenerationStateResponse(
    @field:Schema(description = "IN_PROGRESS = 배치 진행 중(음식은 PENDING_IMAGE 로 숨김) · FAILED = 마지막 생성 실패", example = "FAILED")
    val state: State,
    @field:Schema(
        description = "제출 시 기록한 의도. REPLACE_BETTER 실패는 READY 로 복원됐어도 여기 남는다 · WRONG_IMAGE 실패는 숨김 유지 · " +
            "null 은 의도 없이 제출(구 어드민·일괄 제출·신규 음식 첫 생성)",
        example = "WRONG_IMAGE",
        nullable = true,
    )
    val intent: String?,
    @field:Schema(description = "제출 시 남긴 사유", example = "배경이 어두움", nullable = true)
    val reason: String?,
    @field:Schema(description = "상태가 마지막으로 바뀐 시각(제출 또는 실패 판정)", example = "2026-09-22T22:50:00")
    val at: LocalDateTime,
) {
    enum class State { IN_PROGRESS, FAILED }
}

@Schema(
    description = "이 이미지를 만든 이미지 생성 배치 항목의 입력 — 결과 이미지(image_key)와 배치 항목(file_name)으로 연결한다. " +
        "생성 이력이 없는 이미지(직접 등록·이력 정리)면 null. regeneration(진행·실패 상태)과 별개로 성공한 생성도 보인다",
)
data class AdminImageGenerationResponse(
    @field:Schema(description = "제출 시 의도 — REPLACE_BETTER·WRONG_IMAGE·ADDITIONAL, 의도 없이 제출(일괄·첫 생성)이면 null", example = "WRONG_IMAGE", nullable = true)
    val intent: String?,
    @field:Schema(description = "제출 시 남긴 관리자 수정 지시(사유)", example = "막국수는 보통 빨간 비빔 양념으로 되어있음.", nullable = true)
    val reason: String?,
    @field:Schema(description = "생성 프롬프트 버전 — v2 부터 사유를 프롬프트에 싣는다", example = "v2")
    val promptVersion: String,
    @field:Schema(description = "이미지 모델", example = "gpt-image-2")
    val model: String,
    @field:Schema(description = "배치 제출 시각", example = "2026-10-30T09:00:00")
    val submittedAt: LocalDateTime,
    @field:Schema(
        description = "사유가 실제 생성 프롬프트에 실렸는지 — 사유가 있고 v1 이 아닐 때 true. 실렸다는 뜻이지 결과가 지시대로 나왔다는 뜻은 아니다(검수로 확인)",
        example = "true",
    )
    val reasonInPrompt: Boolean,
) {
    companion object {
        fun of(item: ImageBatchItem, batch: ImageBatch) = AdminImageGenerationResponse(
            intent = item.regenerationIntent?.name,
            reason = item.regenerationReason,
            promptVersion = batch.promptVersion,
            model = batch.model,
            submittedAt = batch.submittedAt,
            reasonInPrompt = !item.regenerationReason.isNullOrBlank() && batch.promptVersion != FoodImageProperties.LEGACY_PROMPT_VERSION,
        )
    }
}
